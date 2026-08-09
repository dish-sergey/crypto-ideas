import com.fasterxml.jackson.databind.*;
import java.net.http.*;
import java.net.URI;
import java.time.*;
import java.util.*;

/**
 * Doc 40 Step 2 (§8): recompute IC on micro universe (>=$10k) vs >=$2M, with autocorrelation
 * correction N_eff = N*(1-rho1)/(1+rho1) applied to IC_IR / t-stat.
 * Return = SUM(funding over h) + (basis_t - basis_{t+h}); L=7,h=7,weekly (fixed, doc 35 §3.3).
 */
public class S12IcAutocorr {
    static final HttpClient HC = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static final ObjectMapper M = new ObjectMapper();
    static final long DAY = 86400000L;
    static final int L=7, H=7, STEP=7;

    static JsonNode get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent","curl/8.0").timeout(Duration.ofSeconds(40)).GET().build();
        HttpResponse<String> r = HC.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode()!=200) throw new RuntimeException("HTTP "+r.statusCode());
        return M.readTree(r.body());
    }
    static void candles(String url, Map<Long,Double> closeOut, Map<Long,Double> turnOut) throws Exception {
        JsonNode j = get(url);
        for (JsonNode c : j.get("candles")) {
            long d = c.get("time").asLong()/DAY;
            double close = Double.parseDouble(c.get("close").asText());
            if (closeOut!=null) closeOut.put(d, close);
            if (turnOut!=null) turnOut.put(d, Double.parseDouble(c.get("volume").asText())*close);
        }
    }

    static Map<String,TreeMap<Long,double[]>> fund = new HashMap<>();
    static Map<String,Map<Long,Double>> mark = new HashMap<>(), spot = new HashMap<>(), turn = new HashMap<>();
    static Map<String,Long> firstDay = new HashMap<>();
    static List<String> syms = new ArrayList<>();
    static long d0, dNow;

    public static void main(String[] a) throws Exception {
        long fromSec = Instant.parse("2025-08-06T00:00:00Z").getEpochSecond();
        d0 = fromSec*1000/DAY; dNow = System.currentTimeMillis()/DAY;

        JsonNode kins = get("https://futures.kraken.com/derivatives/api/v3/instruments");
        for (JsonNode n : kins.get("instruments")) {
            String s = n.path("symbol").asText().toUpperCase();
            if (n.path("tradeable").asBoolean(false) && s.startsWith("PF_") && s.endsWith("USD")) syms.add(s);
        }
        System.out.println("Kraken tradeable PF_*USD: "+syms.size());
        int done=0;
        for (String s : syms) {
            try {
                JsonNode kr = get("https://futures.kraken.com/derivatives/api/v4/historicalfundingrates?symbol="+s);
                TreeMap<Long,double[]> fm = new TreeMap<>(); long first=Long.MAX_VALUE;
                for (JsonNode row : kr.get("rates")) {
                    long ts = Instant.parse(row.get("timestamp").asText()).toEpochMilli(); long d=ts/DAY;
                    double[] c = fm.computeIfAbsent(d,x->new double[2]); c[0]+=row.path("relativeFundingRate").asDouble(); c[1]++;
                    first=Math.min(first,d);
                }
                fund.put(s,fm); firstDay.put(s,first);
                Map<Long,Double> mk=new HashMap<>(), sp=new HashMap<>(), tn=new HashMap<>();
                candles("https://futures.kraken.com/api/charts/v1/mark/"+s+"/1d?from="+fromSec, mk, null);
                candles("https://futures.kraken.com/api/charts/v1/spot/"+s+"/1d?from="+fromSec, sp, null);
                candles("https://futures.kraken.com/api/charts/v1/trade/"+s+"/1d?from="+fromSec, null, tn);
                mark.put(s,mk); spot.put(s,sp); turn.put(s,tn);
            } catch(Exception e){}
            if(++done%50==0) System.out.println("  fetched "+done+"/"+syms.size());
            Thread.sleep(75);
        }

        System.out.println("\n===== STEP 2: IC WITH AUTOCORRELATION CORRECTION (§8) =====");
        runIC(2_000_000, "универсум >=$2M (целевой $200k)");
        runIC(10_000,    "универсум >=$10k (микро $1k)");
    }

    static void runIC(double minTurn, String label) {
        List<Double> ic = new ArrayList<>();
        List<Integer> nInstr = new ArrayList<>();
        List<Double> decileSpread = new ArrayList<>(); // top-decile ret - bottom-decile ret (7d)
        for (long t=d0+L; t+H<=dNow; t+=STEP) {
            List<double[]> rows=new ArrayList<>();
            for (String s : syms) {
                TreeMap<Long,double[]> fm=fund.get(s); if(fm==null) continue;
                if (firstDay.getOrDefault(s,Long.MAX_VALUE) > t-60) continue;
                double sSum=0,sN=0; boolean full=true;
                for(long d=t-L; d<t; d++){ double[] c=fm.get(d); if(c==null){full=false;break;} sSum+=c[0]; sN+=c[1]; }
                if(!full||sN==0) continue;
                double rSum=0; boolean full2=true;
                for(long d=t; d<t+H; d++){ double[] c=fm.get(d); if(c==null){full2=false;break;} rSum+=c[0]; }
                if(!full2) continue;
                Double mt=mark.get(s).get(t), st=spot.get(s).get(t), mh=mark.get(s).get(t+H), sh=spot.get(s).get(t+H);
                if(mt==null||st==null||mh==null||sh==null||st==0||sh==0) continue;
                List<Double> tv=new ArrayList<>();
                for(long d=t-30; d<t; d++){ Double v=turn.get(s).get(d); if(v!=null&&v>0) tv.add(v); }
                if(tv.size()<15 || median(tv)<minTurn) continue;
                double ret=rSum + ((mt-st)/st - (mh-sh)/sh);
                rows.add(new double[]{sSum/sN, ret});
            }
            if(rows.size()<8) continue;
            ic.add(spearman(rows)); nInstr.add(rows.size());
            // decile return spread: mean ret top-decile signal - mean ret bottom-decile signal
            rows.sort((x,y)->Double.compare(y[0],x[0]));
            int k=Math.max(1,(int)Math.ceil(rows.size()/10.0));
            double topR=0, botR=0;
            for(int i=0;i<k;i++) topR+=rows.get(i)[1];
            for(int i=0;i<k;i++) botR+=rows.get(rows.size()-1-i)[1];
            decileSpread.add(topR/k - botR/k);
        }
        int n=ic.size();
        double m=mean(ic), sd=std(ic), ir=sd==0?Double.NaN:m/sd;
        double rho1=autocorr1(ic);
        double nEff=n*(1-rho1)/(1+rho1);
        double tRaw=ir*Math.sqrt(n), tEff=ir*Math.sqrt(nEff);
        System.out.printf("%n%s%n", label);
        System.out.printf("  инстр/нед median=%d (min %d,max %d); ребалансов N=%d%n", med(nInstr), Collections.min(nInstr), Collections.max(nInstr), n);
        System.out.printf("  IC_mean=%+.4f  IC_std=%.4f  IC_IR=%+.3f  %%>0=%.0f%%%n", m, sd, ir, 100.0*frac(ic));
        System.out.printf("  rho1(IC_t)=%.3f  N_eff=%.1f  t_raw=%.2f  t_eff=%.2f%n", rho1, nEff, tRaw, tEff);
        double dsMean=mean(decileSpread), dsAnn=dsMean*(365.0/7.0);
        System.out.printf("  ВАЛОВЫЙ спред доходности дециль(верх-низ) = %.3f%%/нед -> %.1f%%/год (это премия ДО издержек, §1.2)%n",
            dsMean*100, dsAnn*100);
    }

    static double autocorr1(List<Double> v){
        int n=v.size(); if(n<3) return Double.NaN; double m=mean(v);
        double num=0,den=0; for(int i=0;i<n;i++){ double d=v.get(i)-m; den+=d*d; if(i>0) num+=(v.get(i)-m)*(v.get(i-1)-m); }
        return den==0?Double.NaN:num/den;
    }
    static double spearman(List<double[]> p){
        int n=p.size(); double[] x=new double[n],y=new double[n];
        for(int i=0;i<n;i++){x[i]=p.get(i)[0];y[i]=p.get(i)[1];}
        return pearson(ranks(x),ranks(y));
    }
    static double[] ranks(double[] v){
        int n=v.length; Integer[] idx=new Integer[n]; for(int i=0;i<n;i++) idx[i]=i;
        Arrays.sort(idx,(x,y)->Double.compare(v[x],v[y]));
        double[] r=new double[n]; int i=0;
        while(i<n){ int j=i; while(j+1<n&&v[idx[j+1]]==v[idx[i]]) j++; double avg=(i+j)/2.0+1; for(int k=i;k<=j;k++) r[idx[k]]=avg; i=j+1; }
        return r;
    }
    static double pearson(double[] x,double[] y){
        int n=x.length; double mx=0,my=0; for(int i=0;i<n;i++){mx+=x[i];my+=y[i];} mx/=n;my/=n;
        double sxy=0,sxx=0,syy=0; for(int i=0;i<n;i++){double dx=x[i]-mx,dy=y[i]-my; sxy+=dx*dy; sxx+=dx*dx; syy+=dy*dy;}
        return (sxx==0||syy==0)?Double.NaN:sxy/Math.sqrt(sxx*syy);
    }
    static double frac(List<Double> v){ int c=0; for(double x:v) if(x>0)c++; return (double)c/v.size(); }
    static int med(List<Integer> v){ List<Integer> s=new ArrayList<>(v); Collections.sort(s); return s.get(s.size()/2); }
    static double mean(List<Double> v){ double s=0; for(double x:v)s+=x; return s/v.size(); }
    static double std(List<Double> v){ if(v.size()<2)return Double.NaN; double m=mean(v),s=0; for(double x:v)s+=(x-m)*(x-m); return Math.sqrt(s/(v.size()-1)); }
    static double median(List<Double> v){ if(v.isEmpty())return 0; List<Double> s=new ArrayList<>(v); Collections.sort(s); int n=s.size(); return n%2==1?s.get(n/2):(s.get(n/2-1)+s.get(n/2))/2; }
}
