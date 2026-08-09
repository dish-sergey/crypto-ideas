import com.fasterxml.jackson.databind.*;
import java.net.http.*;
import java.net.URI;
import java.util.*;

/**
 * Doc 41 Steps A+B: construction (spot coverage of perp universe) + spread snapshot.
 * A (§2): do Kraken spot USD pairs exist & are liquid for the micro perp universe (>=$10k/day)?
 *         -> decides spot+perp (4 legs) vs perps-only (2 legs).
 * B (§3): current bid-ask relative spread per perp, spread-vs-turnover relationship.
 * SNAPSHOT (single point in time) — note in report; several/day would catch dispersion.
 */
public class S12Construction {
    static final HttpClient HC = HttpClient.newBuilder().build();
    static final ObjectMapper M = new ObjectMapper();
    static JsonNode get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent","curl/8.0").GET().build();
        HttpResponse<String> r = HC.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode()!=200) throw new RuntimeException("HTTP "+r.statusCode()+" "+url);
        return M.readTree(r.body());
    }

    public static void main(String[] a) throws Exception {
        // --- perps: bid/ask/volumeQuote/mark from one tickers call ---
        JsonNode ft = get("https://futures.kraken.com/derivatives/api/v3/tickers");
        // base -> [bid, ask, mark, volQuote($24h), bestDepth$]
        Map<String,double[]> perp = new TreeMap<>();
        for (JsonNode t : ft.get("tickers")) {
            String sym = t.path("symbol").asText().toUpperCase();
            if (!sym.startsWith("PF_") || !sym.endsWith("USD")) continue;
            if (t.path("suspended").asBoolean(false)) continue;
            double bid=t.path("bid").asDouble(0), ask=t.path("ask").asDouble(0), mark=t.path("markPrice").asDouble(0);
            double volQ=t.path("volumeQuote").asDouble(0);
            double bidSz=t.path("bidSize").asDouble(0), askSz=t.path("askSize").asDouble(0);
            if (bid<=0||ask<=0||mark<=0) continue;
            String base = t.path("pair").asText().split(":")[0].toUpperCase(); // e.g. SOL:USD
            double bestDepth = Math.min(bidSz,askSz)*mark;
            perp.put(base, new double[]{bid,ask,mark,volQ,bestDepth});
        }
        System.out.println("perps (active PF_*USD): "+perp.size());

        // --- spot USD pairs ---
        JsonNode ap = get("https://api.kraken.com/0/public/AssetPairs");
        Map<String,String> spotAlt = new TreeMap<>(); // base -> altname
        for (var it = ap.get("result").fields(); it.hasNext(); ) {
            var e = it.next(); JsonNode p = e.getValue();
            String ws = p.path("wsname").asText(""); // e.g. SOL/USD
            if (!ws.endsWith("/USD")) continue;
            String base = ws.substring(0, ws.indexOf('/')).toUpperCase();
            spotAlt.put(base, p.path("altname").asText());
        }
        System.out.println("spot */USD pairs: "+spotAlt.size());

        // micro universe = perps with volQuote >= $10k
        List<String> micro = new ArrayList<>();
        for (var e : perp.entrySet()) if (e.getValue()[3] >= 10_000) micro.add(e.getKey());
        System.out.println("micro perp universe (volQuote >= $10k): "+micro.size());

        // spot coverage of micro universe
        List<String> haveSpot = new ArrayList<>();
        for (String b : micro) if (spotAlt.containsKey(b)) haveSpot.add(b);
        // batched spot ticker for coverage turnover + spread
        Map<String,double[]> spot = new HashMap<>(); // base -> [bid,ask,turn$]
        List<String> alts = new ArrayList<>(); List<String> altBases=new ArrayList<>();
        for (String b : haveSpot){ alts.add(spotAlt.get(b)); altBases.add(b); }
        for (int i=0;i<alts.size();i+=40) {
            List<String> chunk = alts.subList(i, Math.min(i+40, alts.size()));
            List<String> cb = altBases.subList(i, Math.min(i+40, altBases.size()));
            try {
                JsonNode tk = get("https://api.kraken.com/0/public/Ticker?pair="+String.join(",",chunk));
                JsonNode res = tk.get("result");
                // Kraken returns keys possibly different from altname; match by iterating result & wsname base
                int idx=0;
                for (var it=res.fields(); it.hasNext(); ) {
                    var e=it.next(); JsonNode v=e.getValue();
                    double ask=v.path("a").get(0).asDouble(), bid=v.path("b").get(0).asDouble();
                    double vol24=v.path("v").get(1).asDouble(), last=v.path("c").get(0).asDouble();
                    // find which base this key maps to via altname match
                    String key=e.getKey();
                    String base=null;
                    for (int j=0;j<chunk.size();j++) if (key.equalsIgnoreCase(chunk.get(j))||key.replace("ZUSD","USD").equalsIgnoreCase(chunk.get(j))) { base=cb.get(j); break; }
                    if (base==null) continue;
                    spot.put(base, new double[]{bid,ask,vol24*last});
                }
            } catch(Exception ex){ System.out.println("spot chunk err: "+ex.getMessage()); }
            Thread.sleep(300);
        }
        int spotLiquid=0; for (String b: haveSpot){ double[] s=spot.get(b); if (s!=null && s[2]>=10_000) spotLiquid++; }

        System.out.println("\n===== STEP A: CONSTRUCTION (§2) =====");
        System.out.printf("micro perps: %d | have spot */USD pair: %d | spot turnover>=$10k: %d%n", micro.size(), haveSpot.size(), spotLiquid);
        System.out.printf("=> spot coverage %.0f%% of micro universe; liquid-spot coverage %.0f%%%n",
            100.0*haveSpot.size()/micro.size(), 100.0*spotLiquid/micro.size());
        System.out.println(spotLiquid < micro.size()*0.5
            ? "DECISION: спот отсутствует/тонкий для большинства -> ВАРИАНТ «ТОЛЬКО ПЕРПЫ» (2 ноги, портфельная нейтральность, измерить остаточную бету)"
            : "DECISION: спот ликвиден для большинства -> вариант «спот+перп» (4 ноги)");

        System.out.println("\n===== STEP B: SPREAD SNAPSHOT (§3) — единичный снимок =====");
        // perp relative spread distribution over micro universe
        List<Double> relS = new ArrayList<>();
        for (String b: micro){ double[] p=perp.get(b); double mid=(p[0]+p[1])/2; relS.add((p[1]-p[0])/mid); }
        Collections.sort(relS);
        System.out.printf("perp rel.spread (micro, n=%d): p25=%.3f%% p50=%.3f%% p75=%.3f%% p90=%.3f%%%n",
            relS.size(), pct(relS,.25)*100, pct(relS,.5)*100, pct(relS,.75)*100, pct(relS,.9)*100);

        // spread vs turnover buckets
        System.out.println("\nspread vs turnover (perp, all active):");
        double[][] buckets = {{0,10_000},{10_000,100_000},{100_000,1_000_000},{1_000_000,1e12}};
        String[] blab = {"<$10k","$10k-100k","$100k-1M",">$1M"};
        for (int bi=0; bi<buckets.length; bi++) {
            List<Double> bs=new ArrayList<>();
            for (var e: perp.entrySet()){ double vq=e.getValue()[3]; if (vq>=buckets[bi][0]&&vq<buckets[bi][1]){ double[] p=e.getValue(); double mid=(p[0]+p[1])/2; bs.add((p[1]-p[0])/mid);} }
            if (bs.isEmpty()){ System.out.printf("  %-10s n=0%n", blab[bi]); continue; }
            Collections.sort(bs);
            System.out.printf("  %-10s n=%3d  median rel.spread=%.3f%%  (полный цикл 2 ноги ~%.2f%%)%n",
                blab[bi], bs.size(), pct(bs,.5)*100, pct(bs,.5)*100*2*2);
        }
        System.out.println("\n(полный цикл 2 ноги = спред×2 ноги×2 [вход+выход]; недельный ребаланс ~52 цикла/год без буфера)");
    }
    static double pct(List<Double> s,double p){ if(s.isEmpty())return 0; int i=(int)Math.round(p*(s.size()-1)); return s.get(Math.max(0,Math.min(s.size()-1,i))); }
}
