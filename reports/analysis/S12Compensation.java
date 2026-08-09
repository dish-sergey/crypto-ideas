import com.fasterxml.jackson.databind.*;
import java.net.http.*;
import java.net.URI;
import java.time.*;
import java.util.*;

/**
 * Doc 42 Step C0: compensation test. The perps-only strategy shorts top-funding decile, longs bottom.
 * Gross (ex costs) = funding differential (FS) - adverse price move of the shorted names (PS).
 *   FS = mean(Sigma funding | top) - mean(Sigma funding | bottom)      [short collects, long pays]
 *   PS = mean(priceRet | top)      - mean(priceRet | bottom)           [short loses if top rises]
 *   NET = FS - PS   (perps-only: no spot leg, so position P&L = funding + own price move)
 * priceRet = (mark_{t+h} - mark_t)/mark_t.  L=7,h=7,weekly. Both universes (>=$10k, >=$2M).
 * Continue criterion §3.3: NET >= 20pp/yr on some capital level, else close.
 */
public class S12Compensation {
    static final HttpClient HC = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static final ObjectMapper M = new ObjectMapper();
    static final long DAY = 86400000L;
    static final int L=7, H=7, STEP=7;
    static final double ANN = 365.0/7.0;

    static JsonNode get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent","curl/8.0").timeout(Duration.ofSeconds(40)).GET().build();
        HttpResponse<String> r = HC.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode()!=200) throw new RuntimeException("HTTP "+r.statusCode());
        return M.readTree(r.body());
    }
    static void candles(String url, Map<Long,Double> out) throws Exception {
        JsonNode j = get(url);
        for (JsonNode c : j.get("candles")) out.put(c.get("time").asLong()/DAY, Double.parseDouble(c.get("close").asText()));
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
                    long ts=Instant.parse(row.get("timestamp").asText()).toEpochMilli(); long d=ts/DAY;
                    double[] c=fm.computeIfAbsent(d,x->new double[2]); c[0]+=row.path("relativeFundingRate").asDouble(); c[1]++;
                    first=Math.min(first,d);
                }
                fund.put(s,fm); firstDay.put(s,first);
                Map<Long,Double> mk=new HashMap<>(), sp=new HashMap<>(), tn=new HashMap<>();
                candles("https://futures.kraken.com/api/charts/v1/mark/"+s+"/1d?from="+fromSec, mk);
                candles("https://futures.kraken.com/api/charts/v1/spot/"+s+"/1d?from="+fromSec, sp);
                JsonNode tj=get("https://futures.kraken.com/api/charts/v1/trade/"+s+"/1d?from="+fromSec);
                for (JsonNode c: tj.get("candles")){ double cl=Double.parseDouble(c.get("close").asText()); tn.put(c.get("time").asLong()/DAY, Double.parseDouble(c.get("volume").asText())*cl); }
                mark.put(s,mk); spot.put(s,sp); turn.put(s,tn);
            } catch(Exception e){}
            if(++done%50==0) System.out.println("  fetched "+done+"/"+syms.size());
            Thread.sleep(75);
        }
        System.out.println("\n===== STEP C0: COMPENSATION TEST (§3) =====");
        System.out.println("perps-only: NET = FS(funding differential) - PS(adverse price move of shorted top-funding names)");
        run(2_000_000, "универсум >=$2M (целевой $200k)");
        run(10_000,    "универсум >=$10k (микро $1k)");
    }

    static double winsor(double x){ return Math.max(-0.5, Math.min(0.5, x)); } // 2x-liquidation bound

    static void run(double minTurn, String label) {
        List<Double> FSs=new ArrayList<>(), PSs=new ArrayList<>(), NETs=new ArrayList<>();
        List<Double> PSw=new ArrayList<>(), NETw=new ArrayList<>(); // winsorized price returns
        double worstPrice=0; String worstName="";
        for (long t=d0+L; t+H<=dNow; t+=STEP) {
            List<double[]> rows=new ArrayList<>(); // [signal, fundRet, priceRet]
            for (String s : syms) {
                TreeMap<Long,double[]> fm=fund.get(s); if(fm==null) continue;
                if (firstDay.getOrDefault(s,Long.MAX_VALUE) > t-60) continue;
                double sSum=0,sN=0; boolean full=true;
                for(long d=t-L; d<t; d++){ double[] c=fm.get(d); if(c==null){full=false;break;} sSum+=c[0]; sN+=c[1]; }
                if(!full||sN==0) continue;
                double rSum=0; boolean full2=true;
                for(long d=t; d<t+H; d++){ double[] c=fm.get(d); if(c==null){full2=false;break;} rSum+=c[0]; }
                if(!full2) continue;
                Double mt=mark.get(s).get(t), mh=mark.get(s).get(t+H);
                if(mt==null||mh==null||mt==0) continue;
                List<Double> tv=new ArrayList<>();
                for(long d=t-30; d<t; d++){ Double v=turn.get(s).get(d); if(v!=null&&v>0) tv.add(v); }
                if(tv.size()<15 || median(tv)<minTurn) continue;
                double pr=(mh-mt)/mt;
                if(Math.abs(pr)>Math.abs(worstPrice)){ worstPrice=pr; worstName=s; }
                rows.add(new double[]{sSum/sN, rSum, pr});
            }
            if(rows.size()<8) continue;
            rows.sort((x,y)->Double.compare(y[0],x[0]));
            int k=Math.max(1,(int)Math.ceil(rows.size()/10.0));
            double fTop=0,fBot=0,pTop=0,pBot=0,pTopW=0,pBotW=0;
            for(int i=0;i<k;i++){ fTop+=rows.get(i)[1]; pTop+=rows.get(i)[2]; pTopW+=winsor(rows.get(i)[2]); }
            for(int i=0;i<k;i++){ int j=rows.size()-1-i; fBot+=rows.get(j)[1]; pBot+=rows.get(j)[2]; pBotW+=winsor(rows.get(j)[2]); }
            double FS=fTop/k-fBot/k, PS=pTop/k-pBot/k, PSwk=pTopW/k-pBotW/k;
            FSs.add(FS); PSs.add(PS); NETs.add(FS-PS);
            PSw.add(PSwk); NETw.add(FS-PSwk);
        }
        double fsA=mean(FSs)*ANN, psA=mean(PSs)*ANN, netA=mean(NETs)*ANN;
        double psWA=mean(PSw)*ANN, netWA=mean(NETw)*ANN;
        Collections.sort(NETs);
        System.out.printf("%n%s  (ребалансов N=%d)%n", label, NETs.size());
        System.out.printf("  FS funding-спред верх-низ         = %+7.1f%%/год%n", fsA*100);
        System.out.printf("  PS ценовой спред (raw)            = %+7.1f%%/год   PS/FS=%.0f%%%n", psA*100, fsA!=0?100*psA/fsA:0);
        System.out.printf("  PS ценовой спред (winsor ±50%%)    = %+7.1f%%/год   <-- робастная оценка%n", psWA*100);
        System.out.printf("  NET raw = FS-PS                   = %+7.1f%%/год   %%нед NET>0=%.0f%%%n", netA*100, 100.0*fracPos(NETs));
        System.out.printf("  NET winsor                        = %+7.1f%%/год%n", netWA*100);
        System.out.printf("  недельный NET: min=%+.1f%% max=%+.1f%% std=%.1f%% (в нед.единицах)%n",
            NETs.get(0)*100, NETs.get(NETs.size()-1)*100, std(NETs)*100);
        System.out.printf("  крупнейший ценовой ход в универсуме: %s %+.0f%% за неделю (источник хвоста)%n", worstName, worstPrice*100);
        System.out.printf("  критерий §3.3 (NET winsor >=20пп): %s%n", netWA*100>=20 ? "ПРОЙДЕН" : "НЕ ПРОЙДЕН");
    }
    static double std(List<Double> v){ if(v.size()<2)return 0; double m=mean(v),s=0; for(double x:v)s+=(x-m)*(x-m); return Math.sqrt(s/(v.size()-1)); }

    static double fracPos(List<Double> v){ int c=0; for(double x:v) if(x>0)c++; return (double)c/v.size(); }
    static double mean(List<Double> v){ double s=0; for(double x:v)s+=x; return s/v.size(); }
    static double median(List<Double> v){ if(v.isEmpty())return 0; List<Double> s=new ArrayList<>(v); Collections.sort(s); int n=s.size(); return n%2==1?s.get(n/2):(s.get(n/2-1)+s.get(n/2))/2; }
}
