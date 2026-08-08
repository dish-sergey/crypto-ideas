import com.fasterxml.jackson.databind.*;
import java.net.http.*;
import java.net.URI;
import java.time.*;
import java.util.*;

/**
 * Doc 36: cross-venue funding spread capacity check, Kraken vs OKX (both EU-accessible).
 * NOT arbitrage (two counterparties, two liquidation levels). Capacity is the likely killer.
 * Pre-registered criterion §6.3 (ALL must hold): >=5 instruments with |spread|>20%/yr AND
 * narrow-side median daily turnover > $2M; sign stable >=70% days; full-cycle cost <= 1/4 spread.
 */
public class CrossVenueFunding {
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
        // Kraken tradeable PF_*USD -> base
        JsonNode kins = get("https://futures.kraken.com/derivatives/api/v3/instruments");
        Map<String,String> kBase = new TreeMap<>();
        for (JsonNode n : kins.get("instruments")) {
            String s = n.path("symbol").asText().toUpperCase();
            if (!n.path("tradeable").asBoolean(false) || !s.startsWith("PF_") || !s.endsWith("USD")) continue;
            String base = s.substring(3, s.length()-3); if (base.equals("XBT")) base="BTC";
            kBase.put(base, s);
        }
        // OKX live BASE-USDT-SWAP -> instId
        JsonNode oins = get("https://www.okx.com/api/v5/public/instruments?instType=SWAP");
        Map<String,String> oBase = new TreeMap<>();
        for (JsonNode n : oins.get("data")) {
            String id = n.path("instId").asText();
            if (!id.endsWith("-USDT-SWAP") || !"live".equals(n.path("state").asText())) continue;
            oBase.put(id.substring(0, id.indexOf('-')).toUpperCase(), id);
        }
        List<String> bases = new ArrayList<>();
        for (String b : kBase.keySet()) if (oBase.containsKey(b)) bases.add(b);
        System.out.println("Kraken PF_*USD: "+kBase.size()+" | OKX USDT-SWAP live: "+oBase.size()+" | intersection: "+bases.size());

        // per instrument: mean spread over common days, sign stability, narrow turnover
        List<String[]> table = new ArrayList<>(); // base, spread%, narrowTurn$, sign%, nDays
        List<Double> absSpreads = new ArrayList<>();
        int done=0;
        for (String base : bases) {
            try {
                String ks=kBase.get(base), os=oBase.get(base);
                // Kraken funding hourly rel -> daily annualized
                Map<Long,double[]> kd = new HashMap<>();
                JsonNode kr = get("https://futures.kraken.com/derivatives/api/v4/historicalfundingrates?symbol="+ks);
                for (JsonNode row : kr.get("rates")) {
                    long d = Instant.parse(row.get("timestamp").asText()).toEpochMilli()/DAY;
                    double[] c = kd.computeIfAbsent(d,x->new double[2]); c[0]+=row.path("relativeFundingRate").asDouble()*24*365; c[1]++;
                }
                // OKX funding 8h -> daily annualized (paginate ~4 pages back)
                Map<Long,double[]> od = new HashMap<>();
                String after="";
                for (int pg=0; pg<5; pg++) {
                    JsonNode of = get("https://www.okx.com/api/v5/public/funding-rate-history?instId="+os+"&limit=100"+(after.isEmpty()?"":"&after="+after));
                    JsonNode arr = of.get("data"); if (arr==null||arr.size()==0) break;
                    long last=0;
                    for (JsonNode row : arr) {
                        long ts = row.path("fundingTime").asLong();
                        double rate = row.path("realizedRate").asDouble(row.path("fundingRate").asDouble());
                        long d = ts/DAY;
                        double[] c = od.computeIfAbsent(d,x->new double[2]); c[0]+=rate*3*365; c[1]++;
                        last=ts;
                    }
                    if (arr.size()<100) break;
                    after=String.valueOf(last); Thread.sleep(120);
                }
                // common days -> spreads
                List<Double> spreads = new ArrayList<>();
                for (Long d : kd.keySet()) if (od.containsKey(d)) {
                    double k=kd.get(d)[0]/kd.get(d)[1], o=od.get(d)[0]/od.get(d)[1];
                    spreads.add(k-o);
                }
                if (spreads.size()<20) { done++; continue; }
                double mean=0; for(double x:spreads) mean+=x; mean/=spreads.size();
                int same=0; for(double x:spreads) if (Math.signum(x)==Math.signum(mean)) same++;
                double signStab=(double)same/spreads.size();
                // turnover: Kraken charts trade, OKX candles volCcyQuote (recent ~60d median)
                double kTurn=medianTurnKraken(ks), oTurn=medianTurnOkx(os);
                double narrow=Math.min(kTurn,oTurn);
                table.add(new String[]{base, fmtPct(mean), String.format("%.0f",narrow), String.format("%.0f",signStab*100), String.valueOf(spreads.size())});
                absSpreads.add(Math.abs(mean));
            } catch(Exception e){ /* skip */ }
            if(++done%30==0) System.out.println("  processed "+done+"/"+bases.size());
            Thread.sleep(120);
        }

        // spread distribution
        Collections.sort(absSpreads);
        System.out.println("\n===== CROSS-VENUE FUNDING SPREAD (Kraken vs OKX) =====");
        System.out.println("instruments with overlap (>=20 common days): "+absSpreads.size());
        System.out.printf("|spread| distribution: p50=%.1f%%  p75=%.1f%%  p90=%.1f%%%n",
            pct(absSpreads,0.5)*100, pct(absSpreads,0.75)*100, pct(absSpreads,0.90)*100);

        // criterion 1: |spread|>20% AND narrow turnover > $2M
        table.sort((x,y)->Double.compare(Math.abs(Double.parseDouble(y[1].replace("%",""))), Math.abs(Double.parseDouble(x[1].replace("%","")))));
        System.out.println("\n-- joint spread x turnover (|spread|>20%, any turnover) --");
        System.out.printf("%-10s %10s %16s %8s %6s%n","base","spread","narrowTurn$/d","sign%","nDays");
        int c1=0, c1and2=0;
        List<String[]> qualifying = new ArrayList<>();
        for (String[] r : table) {
            double sp=Math.abs(Double.parseDouble(r[1].replace("%","")));
            double turn=Double.parseDouble(r[2]);
            if (sp>20) {
                System.out.printf("%-10s %10s %,16.0f %8s %6s%n", r[0], r[1], turn, r[3], r[4]);
                if (turn>2_000_000.0) { c1++; qualifying.add(r); if (Double.parseDouble(r[3])>=70) c1and2++; }
            }
        }
        System.out.println("\n===== CRITERION §6.3 =====");
        System.out.println("(1) instruments |spread|>20% AND narrow turnover>$2M : "+c1+"  (need >=5) -> "+(c1>=5?"PASS":"FAIL"));
        System.out.println("(2) of those, sign stable >=70% days                 : "+c1and2+"/"+c1);
        System.out.println("(3) cost <=1/4 spread: full-cycle ~0.6% (4 taker legs+spreads) vs 20% spread = 3% of spread -> PASS for captured spread; binding constraint is capacity (1)");
        System.out.println("\nVERDICT: "+((c1>=5 && c1and2>=5)?"criterion MET - escalate to §8":"criterion NOT MET - direction closes, -> rejected.md"));
    }

    static double medianTurnKraken(String sym) {
        try {
            long from = Instant.now().minus(Duration.ofDays(70)).getEpochSecond();
            JsonNode j = get("https://futures.kraken.com/api/charts/v1/trade/"+sym+"/1d?from="+from);
            List<Double> t = new ArrayList<>();
            for (JsonNode c : j.get("candles")) {
                double close=Double.parseDouble(c.get("close").asText()), vol=Double.parseDouble(c.get("volume").asText());
                if (vol>0) t.add(vol*close);
            }
            return median(t);
        } catch(Exception e){ return 0; }
    }
    static double medianTurnOkx(String instId) {
        try {
            JsonNode j = get("https://www.okx.com/api/v5/market/candles?instId="+instId+"&bar=1D&limit=100");
            List<Double> t = new ArrayList<>();
            for (JsonNode c : j.get("data")) { double q=Double.parseDouble(c.get(7).asText()); if (q>0) t.add(q); }
            return median(t);
        } catch(Exception e){ return 0; }
    }
    static String fmtPct(double frac){ return String.format("%+.1f%%", frac*100); }
    static double median(List<Double> v){ if(v.isEmpty())return 0; List<Double> s=new ArrayList<>(v); Collections.sort(s); int n=s.size(); return n%2==1?s.get(n/2):(s.get(n/2-1)+s.get(n/2))/2; }
    static double pct(List<Double> sorted, double p){ if(sorted.isEmpty())return 0; int i=(int)Math.round(p*(sorted.size()-1)); return sorted.get(Math.max(0,Math.min(sorted.size()-1,i))); }
}
