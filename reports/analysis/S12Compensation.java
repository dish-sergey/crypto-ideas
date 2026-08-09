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
    static Map<String,Map<Long,double[]>> markHL = new HashMap<>(); // day -> [high, low]
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
                Map<Long,double[]> mhl=new HashMap<>();
                JsonNode mj=get("https://futures.kraken.com/api/charts/v1/mark/"+s+"/1d?from="+fromSec);
                for (JsonNode c: mj.get("candles")){ long d=c.get("time").asLong()/DAY;
                    mk.put(d, Double.parseDouble(c.get("close").asText()));
                    mhl.put(d, new double[]{Double.parseDouble(c.get("high").asText()), Double.parseDouble(c.get("low").asText())}); }
                markHL.put(s, mhl);
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

    static final double LIQ = 0.50; // adverse move that wipes 2x margin (=-100% leg capital)

    static void run(double minTurn, String label) {
        List<Double> FSs=new ArrayList<>(), PSs=new ArrayList<>();
        List<Double> NETclose=new ArrayList<>(), NETliq=new ArrayList<>();
        int shortLiq=0, longLiq=0, legWeeks=0;
        // §2.1 diagnostic: single (name,week) contributions to weekly PS
        double worstWeekPS=0; String worstWeekLabel=""; List<Double> weeklyPS=new ArrayList<>();
        for (long t=d0+L; t+H<=dNow; t+=STEP) {
            // row: [signal, fundRet, priceRetClose, shortAdverse, longAdverse, nameIdx]
            List<double[]> rows=new ArrayList<>(); List<String> names=new ArrayList<>();
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
                // intra-week extremes of mark over [t, t+H)
                double maxHi=mt, minLo=mt; Map<Long,double[]> hl=markHL.get(s);
                for(long d=t; d<t+H; d++){ double[] x=hl==null?null:hl.get(d); if(x!=null){ maxHi=Math.max(maxHi,x[0]); minLo=Math.min(minLo,x[1]); } }
                double shortAdv=(maxHi-mt)/mt, longAdv=(mt-minLo)/mt;
                rows.add(new double[]{sSum/sN, rSum, (mh-mt)/mt, shortAdv, longAdv}); names.add(s);
            }
            if(rows.size()<8) continue;
            // sort indices by signal desc
            Integer[] ord=new Integer[rows.size()]; for(int i=0;i<ord.length;i++) ord[i]=i;
            Arrays.sort(ord,(x,y)->Double.compare(rows.get(y)[0],rows.get(x)[0]));
            int k=Math.max(1,(int)Math.ceil(rows.size()/10.0));
            double fTop=0,fBot=0,pTop=0,pBot=0;               // close-based
            double sPxLiq=0, lPxLiq=0;                         // liquidation-based price outcome
            for(int i=0;i<k;i++){
                double[] r=rows.get(ord[i]);                  // TOP decile -> SHORT
                fTop+=r[1]; pTop+=r[2];
                boolean liq = r[3]>=LIQ;                       // intra-week high hit +50%
                sPxLiq += liq ? -LIQ : -r[2];                 // liq: lose 100% margin = -50% notional; else short price P&L
                if(liq) shortLiq++;
                double contrib = r[2]/k;                       // this name's contribution to weekly PS (top side, +)
                if(Math.abs(contrib*ANN)>Math.abs(worstWeekPS)){ worstWeekPS=contrib*ANN; worstWeekLabel=names.get(ord[i])+" @wk"+((t-d0)/7)+" priceRet="+String.format("%.0f%%",r[2]*100); }
                legWeeks++;
            }
            for(int i=0;i<k;i++){
                double[] r=rows.get(ord[rows.size()-1-i]);    // BOTTOM decile -> LONG
                fBot+=r[1]; pBot+=r[2];
                boolean liq = r[4]>=LIQ;                       // intra-week low hit -50%
                lPxLiq += liq ? -LIQ : r[2];
                if(liq) longLiq++;
            }
            double FS=fTop/k-fBot/k, PS=pTop/k-pBot/k;
            FSs.add(FS); PSs.add(PS);
            NETclose.add(FS-PS);
            NETliq.add(FS + sPxLiq/k + lPxLiq/k);              // FS + liquidation-aware price P&L (short+long legs)
            weeklyPS.add(PS*ANN);
        }
        double fsA=mean(FSs)*ANN, psA=mean(PSs)*ANN, netCA=mean(NETclose)*ANN, netLA=mean(NETliq)*ANN;
        double liqPerYr=(shortLiq+longLiq)*(52.0/NETliq.size());
        Collections.sort(weeklyPS);
        System.out.printf("%n%s  (ребалансов N=%d)%n", label, NETliq.size());
        System.out.printf("  FS funding-спред                  = %+7.1f%%/год%n", fsA*100);
        System.out.printf("  PS ценовой спред (close)          = %+7.1f%%/год%n", psA*100);
        System.out.printf("  §2.1 недельный PS: медиана=%+.0f%% макс|нед|-вклад: %s%n", median(weeklyPS), worstWeekLabel);
        System.out.printf("  NET (close, БЕЗ ликвидаций)       = %+7.1f%%/год%n", netCA*100);
        System.out.printf("  NET (модель ликвидации §2.3)      = %+7.1f%%/год   <-- честная оценка%n", netLA*100);
        System.out.printf("  ликвидаций ног: шорт=%d, лонг=%d -> ~%.1f/год (%.1f%% нога-недель)%n",
            shortLiq, longLiq, liqPerYr, 100.0*(shortLiq+longLiq)/(2*legWeeks));
        System.out.printf("  критерий §3.3 (NET_liq >=20пп): %s%n", netLA*100>=20 ? "ПРОЙДЕН" : "НЕ ПРОЙДЕН");
    }

    static double fracPos(List<Double> v){ int c=0; for(double x:v) if(x>0)c++; return (double)c/v.size(); }
    static double mean(List<Double> v){ double s=0; for(double x:v)s+=x; return s/v.size(); }
    static double median(List<Double> v){ if(v.isEmpty())return 0; List<Double> s=new ArrayList<>(v); Collections.sort(s); int n=s.size(); return n%2==1?s.get(n/2):(s.get(n/2-1)+s.get(n/2))/2; }
}
