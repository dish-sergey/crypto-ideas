import com.fasterxml.jackson.databind.*;
import java.net.http.*;
import java.net.URI;
import java.time.*;
import java.util.*;

/**
 * Doc 44 Priority 1: momentum transferability Kraken <-> Binance.
 * Unlike funding (venue property, rho=0.163), price is an asset property arbitraged across venues,
 * so momentum RANKS should transfer -> Binance 7yr becomes a valid S2/S9 validation set.
 * Test = same as funding: daily cross-section Spearman of momentum signal between venues. Expect >0.9.
 * Momentum = trailing return over W days. Overlap year 2025-08 -> 2026-08.
 */
public class MomentumTransfer {
    static final HttpClient HC = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static final ObjectMapper M = new ObjectMapper();
    static final long DAY = 86400000L;

    static JsonNode get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent","curl/8.0").timeout(Duration.ofSeconds(40)).GET().build();
        HttpResponse<String> r = HC.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode()!=200) throw new RuntimeException("HTTP "+r.statusCode());
        return M.readTree(r.body());
    }

    public static void main(String[] a) throws Exception {
        long fromSec = Instant.parse("2025-08-06T00:00:00Z").getEpochSecond();
        long fromMs = fromSec*1000;
        long dNow = System.currentTimeMillis()/DAY;

        JsonNode kins = get("https://futures.kraken.com/derivatives/api/v3/instruments");
        Map<String,String> kBase = new TreeMap<>();
        for (JsonNode n : kins.get("instruments")) {
            String s = n.path("symbol").asText().toUpperCase();
            if (!n.path("tradeable").asBoolean(false) || !s.startsWith("PF_") || !s.endsWith("USD")) continue;
            String base = s.substring(3, s.length()-3); if (base.equals("XBT")) base="BTC";
            kBase.put(base, s);
        }
        JsonNode binfo = get("https://fapi.binance.com/fapi/v1/exchangeInfo");
        Map<String,String> bBase = new TreeMap<>();
        for (JsonNode n : binfo.get("symbols")) {
            if (!"PERPETUAL".equals(n.path("contractType").asText()) || !"USDT".equals(n.path("quoteAsset").asText()) || !"TRADING".equals(n.path("status").asText())) continue;
            bBase.put(n.path("baseAsset").asText().toUpperCase(), n.path("symbol").asText());
        }
        List<String> bases = new ArrayList<>();
        for (String b : kBase.keySet()) if (bBase.containsKey(b)) bases.add(b);
        System.out.println("intersection: "+bases.size());

        Map<String,Map<Long,Double>> kClose=new HashMap<>(), bClose=new HashMap<>();
        int done=0;
        for (String base : bases) {
            try {
                Map<Long,Double> kc=new HashMap<>();
                JsonNode kj=get("https://futures.kraken.com/api/charts/v1/trade/"+kBase.get(base)+"/1d?from="+fromSec);
                for (JsonNode c: kj.get("candles")) kc.put(c.get("time").asLong()/DAY, Double.parseDouble(c.get("close").asText()));
                kClose.put(base,kc);
                Map<Long,Double> bc=new HashMap<>();
                long st=fromMs;
                for(int pg=0;pg<2;pg++){ JsonNode bj=get("https://fapi.binance.com/fapi/v1/klines?symbol="+bBase.get(base)+"&interval=1d&startTime="+st+"&limit=1000");
                    if(!bj.isArray()||bj.size()==0) break; long last=st;
                    for(JsonNode c: bj){ long ot=c.get(0).asLong(); bc.put(ot/DAY, Double.parseDouble(c.get(4).asText())); last=ot; }
                    if(bj.size()<1000) break; st=last+DAY; }
                bClose.put(base,bc);
            } catch(Exception e){}
            if(++done%50==0) System.out.println("  "+done+"/"+bases.size());
            Thread.sleep(60);
        }

        long d0 = fromMs/DAY;
        System.out.println("\n===== MOMENTUM TRANSFERABILITY Kraken<->Binance =====");
        for (int W : new int[]{30, 90}) {
            List<Double> rhos=new ArrayList<>(); List<double[]> levelPairs=new ArrayList<>();
            for (long t=d0+W; t<=dNow; t+=7) {
                List<double[]> pairs=new ArrayList<>();
                for (String base : bases) {
                    Double kt=kClose.getOrDefault(base,Map.of()).get(t), kp=kClose.getOrDefault(base,Map.of()).get(t-W);
                    Double bt=bClose.getOrDefault(base,Map.of()).get(t), bp=bClose.getOrDefault(base,Map.of()).get(t-W);
                    if(kt==null||kp==null||bt==null||bp==null||kp<=0||bp<=0) continue;
                    double km=kt/kp-1, bm=bt/bp-1;
                    pairs.add(new double[]{km,bm}); levelPairs.add(new double[]{km,bm});
                }
                if(pairs.size()>=8) rhos.add(spearman(pairs));
            }
            System.out.printf("%nlookback W=%dд:  дней=%d%n", W, rhos.size());
            System.out.printf("  средняя Spearman rho = %.3f (std %.3f)  доля дней rho<0.9 = %.0f%%%n",
                mean(rhos), std(rhos), 100.0*frac(rhos,0.9));
            System.out.printf("  уровневая Pearson r (пул) = %.3f (n=%d)%n", pearson(levelPairs), levelPairs.size());
        }
        System.out.println("\nКритерий (аналог funding-теста): rho>0.9 => моментум переносится => Binance 7лет валиден для S2/S9");
    }

    static double spearman(List<double[]> p){ int n=p.size(); double[] x=new double[n],y=new double[n];
        for(int i=0;i<n;i++){x[i]=p.get(i)[0];y[i]=p.get(i)[1];} return pearsonArr(ranks(x),ranks(y)); }
    static double pearson(List<double[]> p){ int n=p.size(); double[] x=new double[n],y=new double[n];
        for(int i=0;i<n;i++){x[i]=p.get(i)[0];y[i]=p.get(i)[1];} return pearsonArr(x,y); }
    static double[] ranks(double[] v){ int n=v.length; Integer[] idx=new Integer[n]; for(int i=0;i<n;i++) idx[i]=i;
        Arrays.sort(idx,(x,y)->Double.compare(v[x],v[y])); double[] r=new double[n]; int i=0;
        while(i<n){ int j=i; while(j+1<n&&v[idx[j+1]]==v[idx[i]]) j++; double avg=(i+j)/2.0+1; for(int k=i;k<=j;k++) r[idx[k]]=avg; i=j+1; } return r; }
    static double pearsonArr(double[] x,double[] y){ int n=x.length; double mx=0,my=0; for(int i=0;i<n;i++){mx+=x[i];my+=y[i];} mx/=n;my/=n;
        double sxy=0,sxx=0,syy=0; for(int i=0;i<n;i++){double dx=x[i]-mx,dy=y[i]-my; sxy+=dx*dy; sxx+=dx*dx; syy+=dy*dy;}
        return (sxx==0||syy==0)?Double.NaN:sxy/Math.sqrt(sxx*syy); }
    static double mean(List<Double> v){ if(v.isEmpty())return Double.NaN; double s=0; for(double x:v)s+=x; return s/v.size(); }
    static double std(List<Double> v){ if(v.size()<2)return Double.NaN; double m=mean(v),s=0; for(double x:v)s+=(x-m)*(x-m); return Math.sqrt(s/(v.size()-1)); }
    static double frac(List<Double> v,double t){ int c=0; for(double x:v) if(x<t)c++; return (double)c/v.size(); }
}
