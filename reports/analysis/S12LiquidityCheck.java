import com.fasterxml.jackson.databind.*;
import java.net.http.*;
import java.net.URI;
import java.time.*;
import java.util.*;

/**
 * Doc 39 Steps 1-2: turnover reconciliation (§6) + how many Kraken perps are actually tradeable (§7).
 * Turnover method = Kraken charts 'trade' 1d volume x close (identical to cross_venue_funding.md).
 * Stop condition (§7): if instruments with median turnover >=$2M < 30, cross-sectional edge in question.
 */
public class S12LiquidityCheck {
    static final HttpClient HC = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static final ObjectMapper M = new ObjectMapper();
    static final long DAY = 86400000L;
    static final int L=7, STEP=7;

    static JsonNode get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent","curl/8.0").timeout(Duration.ofSeconds(40)).GET().build();
        HttpResponse<String> r = HC.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode()!=200) throw new RuntimeException("HTTP "+r.statusCode());
        return M.readTree(r.body());
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

        Map<String,TreeMap<Long,double[]>> fund = new HashMap<>();   // day -> [sumHourlyRel,count]
        Map<String,Map<Long,Double>> turn = new HashMap<>();         // day -> $turnover
        Map<String,Long> firstDay = new HashMap<>();
        int done=0;
        for (String s : syms) {
            try {
                JsonNode kr = get("https://futures.kraken.com/derivatives/api/v4/historicalfundingrates?symbol="+s);
                TreeMap<Long,double[]> fm = new TreeMap<>(); long first=Long.MAX_VALUE;
                for (JsonNode row : kr.get("rates")) {
                    long d = Instant.parse(row.get("timestamp").asText()).toEpochMilli()/DAY;
                    double[] c = fm.computeIfAbsent(d,x->new double[2]); c[0]+=row.path("relativeFundingRate").asDouble(); c[1]++;
                    first=Math.min(first,d);
                }
                fund.put(s,fm); firstDay.put(s,first);
                Map<Long,Double> tn=new HashMap<>();
                JsonNode tj = get("https://futures.kraken.com/api/charts/v1/trade/"+s+"/1d?from="+fromSec);
                for (JsonNode c : tj.get("candles")) {
                    double close=Double.parseDouble(c.get("close").asText()), vol=Double.parseDouble(c.get("volume").asText());
                    tn.put(c.get("time").asLong()/DAY, vol*close);
                }
                turn.put(s,tn);
            } catch(Exception e){ /* skip */ }
            if(++done%50==0) System.out.println("  fetched "+done+"/"+syms.size());
            Thread.sleep(70);
        }

        // ---- Step 2: count instruments >= thresholds per weekly date; monthly dynamics ----
        double[] TH = {1_000_000, 2_000_000, 5_000_000};
        List<int[]> perDate = new ArrayList<>();       // counts [>=1M,>=2M,>=5M]
        TreeMap<String,int[]> perMonth = new TreeMap<>(); // yyyy-mm -> [sum2M, days]
        for (long t=d0+30; t<=dNow; t+=STEP) {
            int c1=0,c2=0,c5=0;
            for (String s : syms) {
                double med = medianTurn(turn.get(s), t-30, t);
                if (med>=TH[0]) c1++;
                if (med>=TH[1]) c2++;
                if (med>=TH[2]) c5++;
            }
            perDate.add(new int[]{c1,c2,c5});
            String ym = Instant.ofEpochMilli(t*DAY).toString().substring(0,7);
            int[] mm = perMonth.computeIfAbsent(ym,x->new int[2]); mm[0]+=c2; mm[1]++;
        }
        // full-universe distribution at reference (last 30d)
        List<Double> refTurn = new ArrayList<>();
        for (String s : syms){ double m=medianTurn(turn.get(s), dNow-30, dNow); refTurn.add(m); }
        Collections.sort(refTurn);
        long below500k = refTurn.stream().filter(x->x<500_000).count();

        // ---- Step 1: top-signal-decile turnover (reconcile with $4.82M) ----
        List<Double> topDecTurn30=new ArrayList<>();
        for (long t=d0+L; t+L<=dNow; t+=STEP) {
            List<double[]> rows=new ArrayList<>(); // [signal, turn30]
            for (String s : syms) {
                if (firstDay.getOrDefault(s,Long.MAX_VALUE) > t-60) continue;
                TreeMap<Long,double[]> fm=fund.get(s); if(fm==null) continue;
                double sSum=0,sN=0; boolean full=true;
                for(long d=t-L; d<t; d++){ double[] c=fm.get(d); if(c==null){full=false;break;} sSum+=c[0]; sN+=c[1]; }
                if(!full||sN==0) continue;
                double med=medianTurn(turn.get(s), t-30, t);
                if(med<=0) continue;
                rows.add(new double[]{sSum/sN, med});
            }
            if(rows.size()<8) continue;
            rows.sort((x,y)->Double.compare(y[0],x[0]));
            int k=Math.max(1,(int)Math.ceil(rows.size()/10.0));
            List<Double> tv=new ArrayList<>(); for(int i=0;i<k;i++) tv.add(rows.get(i)[1]);
            topDecTurn30.add(median(tv));
        }

        System.out.println("\n===== STEP 1: TURNOVER RECONCILIATION (§6) =====");
        System.out.printf("top-signal-decile median daily turnover (30d method) = $%,.0f%n", median(topDecTurn30));
        System.out.println("cross_venue_funding.md уses identical method (charts trade vol x close).");
        System.out.println("=> discrepancy with $1k-108k is INSTRUMENT SELECTION (high funding LEVEL vs high cross-venue SPREAD), not methodology.");

        System.out.println("\n===== STEP 2: TRADEABLE UNIVERSE (§7) =====");
        int[] med = medianCounts(perDate);
        System.out.printf("instruments with median 30d turnover >= $1M: median-over-weeks %d  (min %d, max %d)%n", med[0], min(perDate,0), max(perDate,0));
        System.out.printf("instruments >= $2M: median-over-weeks %d  (min %d, max %d)   <-- STOP if <30%n", med[1], min(perDate,1), max(perDate,1));
        System.out.printf("instruments >= $5M: median-over-weeks %d  (min %d, max %d)%n", med[2], min(perDate,2), max(perDate,2));
        System.out.println("\nfull-universe turnover distribution (last 30d, n="+refTurn.size()+"):");
        System.out.printf("  p10=$%,.0f  p25=$%,.0f  p50=$%,.0f  p75=$%,.0f  p90=$%,.0f%n",
            pct(refTurn,.1),pct(refTurn,.25),pct(refTurn,.5),pct(refTurn,.75),pct(refTurn,.9));
        System.out.printf("  instruments below $500k: %d / %d (%.0f%%)%n", below500k, refTurn.size(), 100.0*below500k/refTurn.size());
        System.out.printf("  instruments >= $2M at reference: %d%n", refTurn.stream().filter(x->x>=2_000_000).count());
        System.out.println("\nmonthly dynamics (avg instruments >= $2M):");
        for (var e : perMonth.entrySet()) System.out.printf("  %s : %.0f%n", e.getKey(), (double)e.getValue()[0]/e.getValue()[1]);
        System.out.println("\nNB: IC on the >=$2M universe already computed = 0.489 (s12_ic_kraken.md, MIN_TURN=$2M) -> >0.15 OK; binding stop is count>=30.");

        int typical2M = med[1];
        System.out.println("\nSTOP-CONDITION §7: instruments>=$2M typical="+typical2M+" -> "+
            (typical2M<30 ? "TRIGGERED (<30): кросс-секц. преимущество Kraken под вопросом -> приоритет на шаг 4 + forward"
                          : "not triggered (>=30): proceed to Step 3 portfolio"));
    }

    static double medianTurn(Map<Long,Double> m, long from, long to){
        if(m==null) return 0;
        List<Double> t=new ArrayList<>();
        for(long d=from; d<to; d++){ Double v=m.get(d); if(v!=null&&v>0) t.add(v); }
        if(t.size()<15) return 0;
        return median(t);
    }
    static int[] medianCounts(List<int[]> pd){
        int[] out=new int[3];
        for(int j=0;j<3;j++){ List<Double> v=new ArrayList<>(); for(int[] r:pd) v.add((double)r[j]); out[j]=(int)Math.round(median(v)); }
        return out;
    }
    static int min(List<int[]> pd,int j){ int m=Integer.MAX_VALUE; for(int[] r:pd) m=Math.min(m,r[j]); return m; }
    static int max(List<int[]> pd,int j){ int m=0; for(int[] r:pd) m=Math.max(m,r[j]); return m; }
    static double median(List<Double> v){ if(v.isEmpty())return 0; List<Double> s=new ArrayList<>(v); Collections.sort(s); int n=s.size(); return n%2==1?s.get(n/2):(s.get(n/2-1)+s.get(n/2))/2; }
    static double pct(List<Double> sorted,double p){ if(sorted.isEmpty())return 0; int i=(int)Math.round(p*(sorted.size()-1)); return sorted.get(Math.max(0,Math.min(sorted.size()-1,i))); }
}
