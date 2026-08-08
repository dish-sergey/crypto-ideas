import com.fasterxml.jackson.databind.*;
import java.net.http.*;
import java.net.URI;
import java.time.*;
import java.util.*;

/**
 * Doc 35 Task A: IC of funding-ranking -> carry position return on Kraken perps, 1yr.
 * Position return = SUM(funding over h) + (basis_t - basis_{t+h}), delta-neutral (long spot / short perp).
 * Params fixed before run: L=7d signal window, h=7d horizon, 7d rebalance.
 * Liquidity filter (doc 35 §5) + mandatory tercile stratification (§3.4).
 */
public class S12IcKraken {
    static final HttpClient HC = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static final ObjectMapper M = new ObjectMapper();
    static final long DAY = 86400000L;
    static final int L=7, H=7, STEP=7;
    static final double MIN_TURN = 2_000_000.0; // $2M median daily turnover
    static final int MIN_AGE = 60;

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

    public static void main(String[] a) throws Exception {
        long fromSec = Instant.parse("2025-08-06T00:00:00Z").getEpochSecond();
        long now = System.currentTimeMillis();
        long d0 = fromSec*1000/DAY, dNow = now/DAY;

        JsonNode kins = get("https://futures.kraken.com/derivatives/api/v3/instruments");
        List<String> syms = new ArrayList<>();
        for (JsonNode n : kins.get("instruments")) {
            String s = n.path("symbol").asText().toUpperCase();
            if (n.path("tradeable").asBoolean(false) && s.startsWith("PF_") && s.endsWith("USD")) syms.add(s);
        }
        System.out.println("Kraken tradeable PF_*USD: "+syms.size());

        Map<String,TreeMap<Long,double[]>> fund = new HashMap<>(); // day -> [sumHourlyRel,count]
        Map<String,Map<Long,Double>> mark = new HashMap<>(), spot = new HashMap<>(), turn = new HashMap<>();
        Map<String,Long> firstDay = new HashMap<>();
        int done=0;
        for (String s : syms) {
            try {
                JsonNode kr = get("https://futures.kraken.com/derivatives/api/v4/historicalfundingrates?symbol="+s);
                TreeMap<Long,double[]> fm = new TreeMap<>();
                long first=Long.MAX_VALUE;
                for (JsonNode row : kr.get("rates")) {
                    long ts = Instant.parse(row.get("timestamp").asText()).toEpochMilli();
                    long d = ts/DAY;
                    double rel = row.path("relativeFundingRate").asDouble();
                    double[] c = fm.computeIfAbsent(d,x->new double[2]); c[0]+=rel; c[1]++;
                    first=Math.min(first,d);
                }
                fund.put(s,fm); firstDay.put(s,first);
                Map<Long,Double> mk=new HashMap<>(), sp=new HashMap<>(), tn=new HashMap<>();
                candles("https://futures.kraken.com/api/charts/v1/mark/"+s+"/1d?from="+fromSec, mk, null);
                candles("https://futures.kraken.com/api/charts/v1/spot/"+s+"/1d?from="+fromSec, sp, null);
                candles("https://futures.kraken.com/api/charts/v1/trade/"+s+"/1d?from="+fromSec, null, tn);
                mark.put(s,mk); spot.put(s,sp); turn.put(s,tn);
            } catch(Exception e){ /* skip instrument on error */ }
            if(++done%40==0) System.out.println("  fetched "+done+"/"+syms.size());
            Thread.sleep(80);
        }

        // helpers on windows
        // signal = mean hourly rel over [t-L,t); ret = sum hourly rel over [t,t+H) + basis_t - basis_{t+H}
        List<Double> icAll=new ArrayList<>(), icFundOnly=new ArrayList<>(), icBasis=new ArrayList<>();
        List<List<Double>> icTerc=Arrays.asList(new ArrayList<>(),new ArrayList<>(),new ArrayList<>());
        List<Double> topDecTurn=new ArrayList<>();
        int rebals=0, minInstr=8;

        for (long t=d0+L; t+H<=dNow; t+=STEP) {
            List<double[]> rows=new ArrayList<>(); // [signal, ret, turnover]
            for (String s : syms) {
                TreeMap<Long,double[]> fm=fund.get(s); if(fm==null) continue;
                if (firstDay.getOrDefault(s,Long.MAX_VALUE) > t-MIN_AGE) continue; // age>=60d
                // funding signal window [t-L,t)
                double sSum=0,sN=0;
                boolean full=true;
                for(long d=t-L; d<t; d++){ double[] c=fm.get(d); if(c==null){full=false;break;} sSum+=c[0]; sN+=c[1]; }
                if(!full||sN==0) continue;
                double signal=sSum/sN;
                // funding return window [t,t+H)
                double rSum=0; boolean full2=true;
                for(long d=t; d<t+H; d++){ double[] c=fm.get(d); if(c==null){full2=false;break;} rSum+=c[0]; }
                if(!full2) continue;
                Double mt=mark.get(s).get(t), st=spot.get(s).get(t), mh=mark.get(s).get(t+H), sh=spot.get(s).get(t+H);
                if(mt==null||st==null||mh==null||sh==null||st==0||sh==0) continue;
                double basisT=(mt-st)/st, basisH=(mh-sh)/sh;
                double basisContrib = basisT - basisH;
                double ret=rSum + basisContrib;
                // liquidity: median daily turnover over [t-30,t)
                List<Double> tv=new ArrayList<>();
                for(long d=t-30; d<t; d++){ Double v=turn.get(s).get(d); if(v!=null&&v>0) tv.add(v); }
                if(tv.size()<15) continue; // need reasonable coverage
                double medTurn=median(tv);
                if(medTurn<MIN_TURN) continue;
                rows.add(new double[]{signal,ret,medTurn,rSum,basisContrib});
            }
            if(rows.size()<minInstr) continue;
            rebals++;
            icAll.add(spearman2(rows,0,1));
            icFundOnly.add(spearman2(rows,0,3));
            icBasis.add(spearman2(rows,0,4));
            // terciles by turnover
            rows.sort((x,y)->Double.compare(x[2],y[2]));
            int n=rows.size(), t1=n/3, t2=2*n/3;
            List<double[]> lo=rows.subList(0,t1), mid=rows.subList(t1,t2), hi=rows.subList(t2,n);
            if(lo.size()>=5) icTerc.get(0).add(spearman2(new ArrayList<>(lo),0,1));
            if(mid.size()>=5) icTerc.get(1).add(spearman2(new ArrayList<>(mid),0,1));
            if(hi.size()>=5) icTerc.get(2).add(spearman2(new ArrayList<>(hi),0,1));
            // top-decile-of-signal median turnover
            rows.sort((x,y)->Double.compare(y[0],x[0]));
            int k=Math.max(1,(int)Math.ceil(n/10.0));
            List<Double> tdt=new ArrayList<>();
            for(int i=0;i<k;i++) tdt.add(rows.get(i)[2]);
            topDecTurn.add(median(tdt));
        }

        System.out.println("\n===== S12 IC KRAKEN (1yr) =====");
        System.out.println("params: L="+L+"d signal, h="+H+"d horizon, "+STEP+"d rebal; return=SUM(funding)+(basis_t-basis_t+h)");
        System.out.println("liquidity filter: median 30d turnover >= $2M, age>=60d, full funding window");
        System.out.println("rebalances with >="+minInstr+" instruments: "+rebals);
        rep("IC all (total return)", icAll);
        rep("IC vs funding-only", icFundOnly);
        rep("IC vs basis-change", icBasis);
        rep("IC tercile LOW turnover", icTerc.get(0));
        rep("IC tercile MID turnover", icTerc.get(1));
        rep("IC tercile HIGH turnover", icTerc.get(2));
        System.out.printf("top-signal-decile median daily turnover = $%,.0f%n", median(topDecTurn));
        System.out.printf("  -> 1%% of turnover position size = $%,.0f%n", median(topDecTurn)*0.01);
    }

    static void rep(String name, List<Double> v){
        v.removeIf(x -> Double.isNaN(x));
        if(v.isEmpty()){ System.out.println(name+": (no data)"); return; }
        double m=mean(v), sd=std(v);
        System.out.printf("%-28s IC_mean=%+.4f  IC_std=%.4f  IC_IR=%+.3f  (n=%d, %%>0=%.0f%%)%n",
            name, m, sd, sd==0?Double.NaN:m/sd, v.size(), 100.0*frac(v));
    }
    static double frac(List<Double> v){ int c=0; for(double x:v) if(x>0)c++; return (double)c/v.size(); }
    static double median(List<Double> v){ if(v.isEmpty())return Double.NaN; List<Double> s=new ArrayList<>(v); Collections.sort(s); int n=s.size(); return n%2==1?s.get(n/2):(s.get(n/2-1)+s.get(n/2))/2; }
    static double spearman2(List<double[]> rows,int ci,int cj){
        int n=rows.size(); double[] x=new double[n],y=new double[n];
        for(int i=0;i<n;i++){x[i]=rows.get(i)[ci];y[i]=rows.get(i)[cj];}
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
    static double mean(List<Double> v){ double s=0; for(double x:v)s+=x; return s/v.size(); }
    static double std(List<Double> v){ if(v.size()<2)return Double.NaN; double m=mean(v),s=0; for(double x:v)s+=(x-m)*(x-m); return Math.sqrt(s/(v.size()-1)); }
}
