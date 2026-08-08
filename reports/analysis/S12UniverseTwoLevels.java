import com.fasterxml.jackson.databind.*;
import java.net.http.*;
import java.net.URI;
import java.time.*;
import java.util.*;

/**
 * Doc 40 Step 1 (§7): tradeable universe at multiple capital levels + min-order check.
 * required_turnover = capital * leverage(2) / positions(20) * 100 (1% rule) = capital * 10.
 * capital {$1k,$10k,$50k,$200k} -> threshold {$10k,$100k,$500k,$2M}.
 * Min order: $100 leg must clear 10^(-contractValueTradePrecision) base units.
 */
public class S12UniverseTwoLevels {
    static final HttpClient HC = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static final ObjectMapper M = new ObjectMapper();
    static final long DAY = 86400000L;

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
        Map<String,Integer> prec = new HashMap<>();
        Map<String,Double> csize = new HashMap<>();
        for (JsonNode n : kins.get("instruments")) {
            String s = n.path("symbol").asText().toUpperCase();
            if (n.path("tradeable").asBoolean(false) && s.startsWith("PF_") && s.endsWith("USD")) {
                syms.add(s);
                prec.put(s, n.path("contractValueTradePrecision").asInt(0));
                csize.put(s, n.path("contractSize").asDouble(1));
            }
        }
        System.out.println("Kraken tradeable PF_*USD: "+syms.size());

        Map<String,Map<Long,Double>> turn = new HashMap<>();
        Map<String,Double> lastPrice = new HashMap<>();
        int done=0;
        for (String s : syms) {
            try {
                Map<Long,Double> tn=new HashMap<>();
                JsonNode tj = get("https://futures.kraken.com/api/charts/v1/trade/"+s+"/1d?from="+fromSec);
                double lastC=0; long lastD=0;
                for (JsonNode c : tj.get("candles")) {
                    double close=Double.parseDouble(c.get("close").asText()), vol=Double.parseDouble(c.get("volume").asText());
                    long d=c.get("time").asLong()/DAY; tn.put(d, vol*close);
                    if (d>=lastD){ lastD=d; lastC=close; }
                }
                turn.put(s,tn); lastPrice.put(s,lastC);
            } catch(Exception e){}
            if(++done%50==0) System.out.println("  fetched "+done+"/"+syms.size());
            Thread.sleep(70);
        }

        double[] caps = {1_000, 10_000, 50_000, 200_000};
        double[] thr  = {10_000, 100_000, 500_000, 2_000_000};

        System.out.println("\n===== STEP 1: UNIVERSE BY CAPITAL LEVEL (§7) =====");
        System.out.println("required_turnover = capital*10 (2x lev, 20 positions, 1% rule)");
        System.out.printf("%-12s %-14s %-22s%n","capital","threshold","N>=thr (median/min/max по неделям)");
        Map<Double,int[]> monthlyFocus10k = null;
        for (int ti=0; ti<thr.length; ti++) {
            List<Integer> counts = new ArrayList<>();
            TreeMap<String,int[]> monthly = new TreeMap<>();
            for (long t=d0+30; t<=dNow; t+=7) {
                int c=0;
                for (String s : syms) if (medianTurn(turn.get(s), t-30, t) >= thr[ti]) c++;
                counts.add(c);
                String ym = Instant.ofEpochMilli(t*DAY).toString().substring(0,7);
                int[] mm = monthly.computeIfAbsent(ym,x->new int[2]); mm[0]+=c; mm[1]++;
            }
            System.out.printf("$%-11.0f $%-13.0f %d / %d / %d%n", caps[ti], thr[ti], med(counts), Collections.min(counts), Collections.max(counts));
            if (ti==0 || ti==thr.length-1) {
                System.out.print("   monthly: ");
                for (var e : monthly.entrySet()) System.out.printf("%s=%.0f ", e.getKey().substring(2), (double)e.getValue()[0]/e.getValue()[1]);
                System.out.println();
            }
        }

        // min-order check: $100 leg vs 10^(-precision) base units
        int passMin=0; List<String> failMin=new ArrayList<>();
        for (String s : syms) {
            double price=lastPrice.getOrDefault(s,0.0); if(price<=0) continue;
            double minUnits = Math.pow(10, -prec.get(s)) * csize.getOrDefault(s,1.0);
            double units100 = 100.0/price;
            if (units100 >= minUnits) passMin++; else failMin.add(s);
        }
        System.out.println("\n===== MIN ORDER ($100 leg) =====");
        System.out.println("instruments where $100 leg clears min contract size: "+passMin+"/"+syms.size());
        if (!failMin.isEmpty()) System.out.println("  fail: "+failMin);
    }

    static double medianTurn(Map<Long,Double> m, long from, long to){
        if(m==null) return 0; List<Double> t=new ArrayList<>();
        for(long d=from; d<to; d++){ Double v=m.get(d); if(v!=null&&v>0) t.add(v); }
        if(t.size()<15) return 0; return median(t);
    }
    static int med(List<Integer> v){ List<Integer> s=new ArrayList<>(v); Collections.sort(s); int n=s.size(); return n==0?0:s.get(n/2); }
    static double median(List<Double> v){ if(v.isEmpty())return 0; List<Double> s=new ArrayList<>(v); Collections.sort(s); int n=s.size(); return n%2==1?s.get(n/2):(s.get(n/2-1)+s.get(n/2))/2; }
}
