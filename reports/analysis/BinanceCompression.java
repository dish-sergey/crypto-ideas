import com.fasterxml.jackson.databind.*;
import java.net.http.*;
import java.net.URI;
import java.time.*;
import java.util.*;

/**
 * Doc 40 Step 3 (§9): is the universe compression (Kraken 27->13 over the year) market-wide or Kraken-specific?
 * Count Binance USDT perps with median daily turnover >= threshold, by month 2025-2026.
 * Monthly klines quoteVolume / days = median daily $ turnover proxy.
 * NB: today's perp list (survivors) -> minor undercount in months with since-delisted names; shape is the question.
 */
public class BinanceCompression {
    static final HttpClient HC = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static final ObjectMapper M = new ObjectMapper();

    static JsonNode get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent","curl/8.0").timeout(Duration.ofSeconds(40)).GET().build();
        HttpResponse<String> r = HC.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode()!=200) throw new RuntimeException("HTTP "+r.statusCode());
        return M.readTree(r.body());
    }

    public static void main(String[] a) throws Exception {
        JsonNode info = get("https://fapi.binance.com/fapi/v1/exchangeInfo");
        List<String> syms = new ArrayList<>();
        for (JsonNode n : info.get("symbols")) {
            if ("PERPETUAL".equals(n.path("contractType").asText()) && "USDT".equals(n.path("quoteAsset").asText())
                && "TRADING".equals(n.path("status").asText())) syms.add(n.path("symbol").asText());
        }
        System.out.println("Binance PERPETUAL USDT TRADING: "+syms.size());

        long start = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli();
        // month -> counts [>=2M, >=10k]
        TreeMap<String,int[]> monthly = new TreeMap<>();
        int done=0;
        for (String s : syms) {
            try {
                JsonNode k = get("https://fapi.binance.com/fapi/v1/klines?symbol="+s+"&interval=1M&startTime="+start+"&limit=30");
                for (JsonNode c : k) {
                    long openT = c.get(0).asLong();
                    double quoteVol = Double.parseDouble(c.get(7).asText());
                    LocalDate ld = Instant.ofEpochMilli(openT).atZone(ZoneOffset.UTC).toLocalDate();
                    int days = ld.lengthOfMonth();
                    double dailyTurn = quoteVol/days;
                    String ym = ld.toString().substring(0,7);
                    int[] c2 = monthly.computeIfAbsent(ym,x->new int[2]);
                    if (dailyTurn>=2_000_000) c2[0]++;
                    if (dailyTurn>=10_000) c2[1]++;
                }
            } catch(Exception e){}
            if(++done%100==0) System.out.println("  fetched "+done+"/"+syms.size());
            Thread.sleep(40);
        }

        System.out.println("\n===== STEP 3: BINANCE UNIVERSE BY MONTH (§9) =====");
        System.out.printf("%-9s %-14s %-14s%n","month","N>=$2M/day","N>=$10k/day");
        for (var e : monthly.entrySet())
            System.out.printf("%-9s %-14d %-14d%n", e.getKey(), e.getValue()[0], e.getValue()[1]);
        System.out.println("\nЧитать: сжимается и на Binance -> рыночное; не сжимается -> отток с Kraken; излом июль-2026 -> MiCA");
    }
}
