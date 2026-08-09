import com.fasterxml.jackson.databind.*;
import java.net.http.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/**
 * Doc 45: S2 cross-sectional momentum on Binance PIT. Binance = mechanism validation (ranks transfer,
 * rho 0.98); execution on Kraken. Long-only 1x top-10 (no liquidation).
 * Step 1 (trivial benchmark FIRST): equal-weight basket of universe + BTC buy&hold.
 * Step 2: S2 signal = (close_{t-K}/close_{t-K-L} - 1) / vol60, K=5,L=30, top-10 EW, weekly, buffer 10/20.
 * Runs A (full Binance >=$2M) and B (intersection with Kraken-tradeable). Subperiods 19-20/21-22/23-26.
 */
public class S2S9Momentum {
    static final HttpClient HC = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static final ObjectMapper M = new ObjectMapper();
    static final long DAY = 86400000L;
    static final int L=30, K=5, VOLW=60, STEP=7, TOP=10, BUF=20;
    static final double MIN_TURN=2_000_000, COST_RT=0.004; // ~0.4% round-trip (spread p75 both sides + comm)

    static JsonNode get(String url) throws Exception {
        for(int at=0;at<4;at++){ HttpRequest req=HttpRequest.newBuilder(URI.create(url)).header("User-Agent","curl/8.0").timeout(Duration.ofSeconds(40)).GET().build();
            HttpResponse<String> r=HC.send(req,HttpResponse.BodyHandlers.ofString());
            if(r.statusCode()==200) return M.readTree(r.body());
            if(r.statusCode()==429||r.statusCode()==418){ Thread.sleep(2000L*(at+1)); continue; }
            throw new RuntimeException("HTTP "+r.statusCode()); }
        throw new RuntimeException("retries"); }
    static String getRaw(String url) throws Exception { HttpRequest req=HttpRequest.newBuilder(URI.create(url)).header("User-Agent","curl/8.0").timeout(Duration.ofSeconds(40)).GET().build(); return HC.send(req,HttpResponse.BodyHandlers.ofString()).body(); }

    static Map<String,TreeMap<Long,double[]>> px=new HashMap<>(); // day->[close,quoteVol]
    static Map<String,Long> firstDay=new HashMap<>();
    static Set<String> krakenBases=new HashSet<>();
    static long dNow;

    public static void main(String[] a) throws Exception {
        dNow=System.currentTimeMillis()/DAY;
        long fromMs=Instant.parse("2019-08-01T00:00:00Z").toEpochMilli();
        List<String> syms=listArchive();
        System.out.println("archive USDT symbols: "+syms.size());
        // Kraken tradeable bases for run B
        JsonNode kins=get("https://futures.kraken.com/derivatives/api/v3/instruments");
        for(JsonNode n:kins.get("instruments")){ String s=n.path("symbol").asText().toUpperCase();
            if(n.path("tradeable").asBoolean(false)&&s.startsWith("PF_")&&s.endsWith("USD")){ String b=s.substring(3,s.length()-3); if(b.equals("XBT"))b="BTC"; krakenBases.add(b); } }
        System.out.println("kraken tradeable bases: "+krakenBases.size());

        int done=0,ok=0;
        for(String s:syms){ try{
            TreeMap<Long,double[]> pm=new TreeMap<>(); long st=fromMs;
            for(int pg=0;pg<6;pg++){ JsonNode kl=get("https://fapi.binance.com/fapi/v1/klines?symbol="+s+"&interval=1d&startTime="+st+"&limit=1500");
                if(!kl.isArray()||kl.size()==0) break; long last=st;
                for(JsonNode c:kl){ long ot=c.get(0).asLong(); pm.put(ot/DAY,new double[]{Double.parseDouble(c.get(4).asText()),Double.parseDouble(c.get(7).asText())}); last=ot; }
                if(kl.size()<1500) break; st=last+DAY; Thread.sleep(35); }
            if(pm.size()>100){ px.put(s,pm); firstDay.put(s,pm.firstKey()); ok++; }
        }catch(Exception e){} if(++done%100==0) System.out.println("  "+done+"/"+syms.size()+" ok "+ok); Thread.sleep(25); }
        System.out.println("symbols with data: "+ok);

        System.out.println("\n===== S2 RAW MOMENTUM (Binance PIT) =====");
        run(false,false,"S2-A: полная Binance PIT (>=$2M)");
        run(true, false,"S2-B: пересечение с Kraken (>=$2M)");
        System.out.println("\n===== S9 RESIDUAL MOMENTUM (регрессия на BTC 90д) =====");
        run(false,true, "S9-A: полная Binance PIT (>=$2M)");
        run(true, true, "S9-B: пересечение с Kraken (>=$2M)");
    }

    // S9: accumulated residual eps of r_i on r_BTC over regression window, normalized by residual std
    static Double residSignal(TreeMap<Long,double[]> pm, long t){
        TreeMap<Long,double[]> btc=px.get("BTCUSDT"); if(btc==null) return null;
        List<double[]> pair=new ArrayList<>(); // [r_i, r_btc]
        Double pi=null, pb=null;
        for(long d=t-90; d<t; d++){ double[] xi=pm.get(d), xb=btc.get(d);
            if(xi!=null&&xb!=null){ if(pi!=null&&pb!=null&&pi>0&&pb>0) pair.add(new double[]{xi[0]/pi-1, xb[0]/pb-1}); pi=xi[0]; pb=xb[0]; }
            else { pi=xi!=null?xi[0]:null; pb=xb!=null?xb[0]:null; } }
        if(pair.size()<40) return null;
        double mi=0,mb=0; for(double[] p:pair){mi+=p[0];mb+=p[1];} mi/=pair.size(); mb/=pair.size();
        double cov=0,varb=0; for(double[] p:pair){ cov+=(p[0]-mi)*(p[1]-mb); varb+=(p[1]-mb)*(p[1]-mb); }
        if(varb<=0) return null; double beta=cov/varb, alpha=mi-beta*mb;
        // residual std
        double rs=0; for(double[] p:pair){ double e=p[0]-alpha-beta*p[1]; rs+=e*e; } rs=Math.sqrt(rs/pair.size()); if(rs<=0) return null;
        // accumulate eps over [t-K-L, t-K)
        double sum=0; int n=0; Double qi=null,qb=null;
        for(long d=t-K-L-1; d<t-K; d++){ double[] xi=pm.get(d), xb=btc.get(d);
            if(xi!=null&&xb!=null){ if(qi!=null&&qb!=null&&qi>0&&qb>0){ double ri=xi[0]/qi-1, rb=xb[0]/qb-1; sum+=ri-alpha-beta*rb; n++; } qi=xi[0]; qb=xb[0]; }
            else { qi=xi!=null?xi[0]:null; qb=xb!=null?xb[0]:null; } }
        if(n<10) return null;
        return sum/rs;
    }

    static String base(String sym){ return sym.endsWith("USDT")?sym.substring(0,sym.length()-4):sym; }

    static void run(boolean krakenOnly, boolean resid, String label){
        long d0=LocalDate.parse("2019-09-16").toEpochDay();
        long b1=LocalDate.parse("2021-01-01").toEpochDay(), b2=LocalDate.parse("2023-01-01").toEpochDay();
        List<double[]> weekly=new ArrayList<>(); // [t, mom_net, mom_gross, basket, btc, ic, decile, nUniv, turnover, gatedNet, gatedGross]
        Set<String> held=new HashSet<>();
        for(long t=d0; t+STEP<=dNow; t+=STEP){
            // regime gate: BTC above SMA200 AND breadth>=0.35
            Double btcC=cl(px.getOrDefault("BTCUSDT",new TreeMap<>()),t), btcSma=sma(px.getOrDefault("BTCUSDT",new TreeMap<>()),t,200);
            boolean btcBull = btcC!=null && btcSma!=null && btcC>btcSma;
            int brUp=0, brTot=0;
            // build selectable universe + signals
            List<double[]> sig=new ArrayList<>(); List<String> nm=new ArrayList<>(); // [signal, fwdRet]
            double basketSum=0; int basketN=0;
            for(String s: px.keySet()){
                if(krakenOnly && !krakenBases.contains(base(s))) continue;
                if(firstDay.get(s)>t-90) continue;
                TreeMap<Long,double[]> pm=px.get(s);
                Double c0=cl(pm,t), cF=cl(pm,t+STEP), cK=cl(pm,t-K), cKL=cl(pm,t-K-L);
                if(c0==null||cF==null||cK==null||cKL==null||cKL<=0||c0<=0) continue;
                // liquidity: median 30d quoteVol
                List<Double> tv=new ArrayList<>(); for(long d=t-30;d<t;d++){ double[] x=pm.get(d); if(x!=null&&x[1]>0) tv.add(x[1]); }
                if(tv.size()<20||median(tv)<MIN_TURN) continue;
                double mom;
                if(resid){ Double rs=residSignal(pm,t); if(rs==null) continue; mom=rs; }
                else { double vol=vol60(pm,t); if(vol<=0) continue; mom=(cK/cKL-1)/vol; }
                double fwd=cF/c0-1;
                sig.add(new double[]{mom,fwd}); nm.add(s);
                basketSum+=fwd; basketN++;
                Double sm=sma(pm,t,200); if(sm!=null){ brTot++; if(c0>sm) brUp++; }
            }
            if(nm.size()<20) continue;
            boolean gateOn = btcBull && brTot>0 && (double)brUp/brTot>=0.35;
            // rank by signal desc
            Integer[] ord=new Integer[nm.size()]; for(int i=0;i<ord.length;i++) ord[i]=i;
            Arrays.sort(ord,(x,y)->Double.compare(sig.get(y)[0],sig.get(x)[0]));
            Map<String,Integer> rank=new HashMap<>(); for(int i=0;i<ord.length;i++) rank.put(nm.get(ord[i]),i+1);
            // buffer: keep held with rank<=BUF, then fill to TOP from top ranks
            Set<String> newHeld=new LinkedHashSet<>();
            for(String h:held) if(rank.getOrDefault(h,999)<=BUF) newHeld.add(h);
            for(int i=0;i<ord.length && newHeld.size()<TOP;i++) newHeld.add(nm.get(ord[i]));
            // turnover vs previous
            int changed=0; for(String h:newHeld) if(!held.contains(h)) changed++;
            double turnover = held.isEmpty()?1.0:(double)changed/TOP;
            // portfolio fwd return = EW of held
            double pSum=0; int pN=0;
            for(String h:newHeld){ int idx=nm.indexOf(h); if(idx>=0){ pSum+=sig.get(idx)[1]; pN++; } }
            double momGross = pN>0?pSum/pN:0;
            double momNet = momGross - turnover*COST_RT;
            held=newHeld;
            // IC + decile spread
            double ic=spearmanFwd(sig);
            int k=Math.max(1,(int)Math.ceil(nm.size()/10.0));
            double dTop=0,dBot=0; for(int i=0;i<k;i++){ dTop+=sig.get(ord[i])[1]; dBot+=sig.get(ord[nm.size()-1-i])[1]; }
            double decile=dTop/k-dBot/k;
            Double btc=btcRet(t);
            weekly.add(new double[]{t, momNet, momGross, basketN>0?basketSum/basketN:0, btc==null?0:btc, ic, decile, nm.size(), turnover,
                gateOn?momNet:0.0, gateOn?momGross:0.0});
        }
        report(label, weekly, d0, b1, b2);
    }

    static void report(String label, List<double[]> w, long d0,long b1,long b2){
        System.out.printf("%n-- %s --  недель=%d%n", label, w.size());
        System.out.printf("  средний размер вселенной=%.0f  средний оборот портфеля=%.0f%%/нед%n",
            avg(w,7), 100*avg(w,8));
        // annualized returns per series (geometric) full + subperiods
        String[] names={"S2 net","S2 gross","корзина EW","BTC B&H","S2 GATED net","S2 GATED grs"}; int[] col={1,2,3,4,9,10};
        for(int c=0;c<col.length;c++){
            System.out.printf("  %-11s CAGR: всё=%+.0f%%  [19-20]=%+.0f%%  [21-22]=%+.0f%%  [23-26]=%+.0f%%  MaxDD=%.0f%%%n",
                names[c], cagr(w,col[c],d0,dNow2(w),0,Long.MAX_VALUE)*100,
                cagr(w,col[c],d0,dNow2(w),0,b1)*100, cagr(w,col[c],d0,dNow2(w),b1,b2)*100, cagr(w,col[c],d0,dNow2(w),b2,Long.MAX_VALUE)*100,
                maxDD(w,col[c])*100);
        }
        // IC with autocorr
        List<Double> ics=col(w,5); double icM=mean(ics), icS=std(ics), rho1=ac1(ics);
        double nEff=ics.size()*(1-rho1)/(1+rho1);
        System.out.printf("  IC_mean=%.3f (std %.3f) rho1=%.2f N_eff=%.1f t_eff=%.2f%n", icM, icS, rho1, nEff, (icS==0?0:icM/icS)*Math.sqrt(Math.max(1,nEff)));
        System.out.printf("  валовый спред дециля (верх-низ) = %+.0f%%/год%n", mean(col(w,6))*(365.0/7)*100);
    }

    // helpers
    static long dNow2(List<double[]> w){ return w.isEmpty()?dNow:(long)w.get(w.size()-1)[0]+STEP; }
    static double cagr(List<double[]> w,int c,long d0,long dEnd,long lo,long hi){
        double eq=1; int n=0; for(double[] r:w){ long t=(long)r[0]; if(t>=lo&&t<hi){ eq*=(1+r[c]); n++; } }
        if(n==0) return 0; double yrs=n*7.0/365.0; return Math.pow(eq,1.0/yrs)-1; }
    static double maxDD(List<double[]> w,int c){ double eq=1,peak=1,mdd=0; for(double[] r:w){ eq*=(1+r[c]); peak=Math.max(peak,eq); mdd=Math.min(mdd,eq/peak-1); } return mdd; }
    static Double cl(TreeMap<Long,double[]> m,long d){ double[] x=m.get(d); if(x!=null) return x[0]; Long f=m.floorKey(d); if(f!=null&&f>=d-3) return m.get(f)[0]; return null; }
    static double vol60(TreeMap<Long,double[]> m,long t){ List<Double> lr=new ArrayList<>(); Double prev=null;
        for(long d=t-VOLW;d<t;d++){ double[] x=m.get(d); if(x!=null){ if(prev!=null&&prev>0) lr.add(Math.log(x[0]/prev)); prev=x[0]; } }
        if(lr.size()<20) return 0; return std(lr); }
    static Double btcRet(long t){ TreeMap<Long,double[]> m=px.get("BTCUSDT"); if(m==null) return null; Double a=cl(m,t),b=cl(m,t+STEP); if(a==null||b==null||a<=0) return null; return b/a-1; }
    static Double sma(TreeMap<Long,double[]> m,long t,int w){ double s=0; int n=0; for(long d=t-w;d<t;d++){ double[] x=m.get(d); if(x!=null){ s+=x[0]; n++; } } return n<w/2?null:s/n; }
    static double spearmanFwd(List<double[]> sig){ int n=sig.size(); double[] x=new double[n],y=new double[n];
        for(int i=0;i<n;i++){x[i]=sig.get(i)[0];y[i]=sig.get(i)[1];} return pear(ranks(x),ranks(y)); }
    static double[] ranks(double[] v){ int n=v.length; Integer[] idx=new Integer[n]; for(int i=0;i<n;i++) idx[i]=i;
        Arrays.sort(idx,(x,y)->Double.compare(v[x],v[y])); double[] r=new double[n]; int i=0;
        while(i<n){ int j=i; while(j+1<n&&v[idx[j+1]]==v[idx[i]]) j++; double av=(i+j)/2.0+1; for(int k2=i;k2<=j;k2++) r[idx[k2]]=av; i=j+1; } return r; }
    static double pear(double[] x,double[] y){ int n=x.length; double mx=0,my=0; for(int i=0;i<n;i++){mx+=x[i];my+=y[i];} mx/=n;my/=n;
        double sxy=0,sxx=0,syy=0; for(int i=0;i<n;i++){double dx=x[i]-mx,dy=y[i]-my; sxy+=dx*dy; sxx+=dx*dx; syy+=dy*dy;} return (sxx==0||syy==0)?0:sxy/Math.sqrt(sxx*syy); }
    static double ac1(List<Double> v){ int n=v.size(); if(n<3) return 0; double m=mean(v),num=0,den=0; for(int i=0;i<n;i++){ double d=v.get(i)-m; den+=d*d; if(i>0) num+=(v.get(i)-m)*(v.get(i-1)-m);} return den==0?0:num/den; }
    static List<Double> col(List<double[]> w,int c){ List<Double> o=new ArrayList<>(); for(double[] r:w) o.add(r[c]); return o; }
    static double avg(List<double[]> w,int c){ double s=0; for(double[] r:w) s+=r[c]; return w.isEmpty()?0:s/w.size(); }
    static double mean(List<Double> v){ if(v.isEmpty())return 0; double s=0; for(double x:v)s+=x; return s/v.size(); }
    static double std(List<Double> v){ if(v.size()<2)return 0; double m=mean(v),s=0; for(double x:v)s+=(x-m)*(x-m); return Math.sqrt(s/(v.size()-1)); }
    static double median(List<Double> v){ if(v.isEmpty())return 0; List<Double> s=new ArrayList<>(v); Collections.sort(s); int n=s.size(); return n%2==1?s.get(n/2):(s.get(n/2-1)+s.get(n/2))/2; }
    static List<String> listArchive() throws Exception { List<String> out=new ArrayList<>(); String marker="";
        for(int pg=0;pg<5;pg++){ String url="https://s3-ap-northeast-1.amazonaws.com/data.binance.vision?delimiter=/&prefix=data/futures/um/monthly/fundingRate/"+(marker.isEmpty()?"":"&marker="+URLEncoder.encode(marker,StandardCharsets.UTF_8));
            String xml=getRaw(url); String last=null; int i=0;
            while(true){ int a=xml.indexOf("<Prefix>",i); if(a<0)break; int b=xml.indexOf("</Prefix>",a); String p=xml.substring(a+8,b); i=b+9;
                if(p.endsWith("/")&&p.contains("fundingRate/")){ String sym=p.substring(p.indexOf("fundingRate/")+12).replace("/",""); if(sym.endsWith("USDT")) out.add(sym); last=p; } }
            if(!xml.contains("<IsTruncated>true</IsTruncated>")||last==null) break; marker=last; Thread.sleep(100); }
        return out; }
}
