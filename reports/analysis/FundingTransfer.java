import com.fasterxml.jackson.databind.*;
import java.net.http.*;
import java.net.URI;
import java.time.*;
import java.util.*;

public class FundingTransfer {
    static final HttpClient HC = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static final ObjectMapper M = new ObjectMapper();
    static final long DAY = 86400000L;

    static JsonNode get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent","curl/8.0").timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<String> r = HC.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode()!=200) throw new RuntimeException("HTTP "+r.statusCode()+" "+url+" :: "+r.body().substring(0,Math.min(200,r.body().length())));
        return M.readTree(r.body());
    }

    public static void main(String[] a) throws Exception {
        long from = Instant.parse("2025-08-06T00:00:00Z").toEpochMilli();
        long now  = System.currentTimeMillis();

        JsonNode kins = get("https://futures.kraken.com/derivatives/api/v3/instruments");
        Map<String,String> kBaseToSym = new TreeMap<>();
        for (JsonNode n : kins.get("instruments")) {
            String s = n.path("symbol").asText().toUpperCase();
            if (!n.path("tradeable").asBoolean(false)) continue;
            if (!s.startsWith("PF_") || !s.endsWith("USD")) continue;
            String base = s.substring(3, s.length()-3);
            if (base.equals("XBT")) base="BTC";
            kBaseToSym.put(base, s);
        }
        System.out.println("Kraken tradeable PF_*USD perps: "+kBaseToSym.size());

        JsonNode binfo = get("https://fapi.binance.com/fapi/v1/exchangeInfo");
        Map<String,String> bBaseToSym = new TreeMap<>();
        for (JsonNode n : binfo.get("symbols")) {
            if (!"PERPETUAL".equals(n.path("contractType").asText())) continue;
            if (!"USDT".equals(n.path("quoteAsset").asText())) continue;
            if (!"TRADING".equals(n.path("status").asText())) continue;
            bBaseToSym.put(n.path("baseAsset").asText().toUpperCase(), n.path("symbol").asText());
        }
        System.out.println("Binance PERPETUAL USDT TRADING: "+bBaseToSym.size());

        List<String> bases = new ArrayList<>();
        for (String b : kBaseToSym.keySet()) if (bBaseToSym.containsKey(b)) bases.add(b);
        System.out.println("Intersection (traded on both): "+bases.size());
        System.out.println(bases);

        Map<String,Double> qv = new HashMap<>();
        try {
            JsonNode t = get("https://fapi.binance.com/fapi/v1/ticker/24hr");
            for (JsonNode n : t) qv.put(n.path("symbol").asText(), n.path("quoteVolume").asDouble(0));
        } catch(Exception e){ System.out.println("24hr vol skip: "+e.getMessage()); }

        Map<String,Map<Long,double[]>> kAgg = new HashMap<>();
        Map<String,Map<Long,double[]>> bAgg = new HashMap<>();
        for (String base : bases) {
            String ks = kBaseToSym.get(base), bs = bBaseToSym.get(base);
            try {
                JsonNode kr = get("https://futures.kraken.com/derivatives/api/v4/historicalfundingrates?symbol="+ks);
                Map<Long,double[]> m = kAgg.computeIfAbsent(base,x->new HashMap<>());
                for (JsonNode row : kr.get("rates")) {
                    long ts = Instant.parse(row.get("timestamp").asText()).toEpochMilli();
                    if (ts<from) continue;
                    double ann = row.path("relativeFundingRate").asDouble()*24*365;
                    long d = ts/DAY;
                    double[] c = m.computeIfAbsent(d,x->new double[2]); c[0]+=ann; c[1]++;
                }
            } catch(Exception e){ System.out.println("K "+ks+" ERR "+e.getMessage()); }
            try {
                Map<Long,double[]> m = bAgg.computeIfAbsent(base,x->new HashMap<>());
                long st = from;
                for (int pg=0; pg<6; pg++) {
                    JsonNode br = get("https://fapi.binance.com/fapi/v1/fundingRate?symbol="+bs+"&startTime="+st+"&limit=1000");
                    if (!br.isArray() || br.size()==0) break;
                    long last=st;
                    for (JsonNode row : br) {
                        long ts = row.path("fundingTime").asLong();
                        double ann = row.path("fundingRate").asDouble()*3*365;
                        long d = ts/DAY;
                        double[] c = m.computeIfAbsent(d,x->new double[2]); c[0]+=ann; c[1]++;
                        last=ts;
                    }
                    if (br.size()<1000) break;
                    st = last+1; Thread.sleep(120);
                }
            } catch(Exception e){ System.out.println("B "+bs+" ERR "+e.getMessage()); }
            Thread.sleep(120);
        }

        long d0 = from/DAY, d1 = now/DAY;
        List<double[]> levelPairs = new ArrayList<>();
        List<Double> rhoDays = new ArrayList<>();
        List<Double> decileOverlap = new ArrayList<>();
        int minInstr = 8;
        List<String> liquid = new ArrayList<>(bases);
        liquid.sort((x,y)->Double.compare(qv.getOrDefault(bBaseToSym.get(y),0.0), qv.getOrDefault(bBaseToSym.get(x),0.0)));
        Set<String> liquidTop = new HashSet<>(liquid.subList(0, Math.min(10, liquid.size())));
        List<Double> rhoLiquid = new ArrayList<>();

        for (long d=d0; d<=d1; d++) {
            List<double[]> pairs = new ArrayList<>();
            List<double[]> pairsLiquid = new ArrayList<>();
            for (String base : bases) {
                double[] kc = kAgg.getOrDefault(base,Map.of()).get(d);
                double[] bc = bAgg.getOrDefault(base,Map.of()).get(d);
                if (kc==null||bc==null||kc[1]==0||bc[1]==0) continue;
                double kv = kc[0]/kc[1], bv=bc[0]/bc[1];
                pairs.add(new double[]{kv,bv});
                levelPairs.add(new double[]{kv,bv});
                if (liquidTop.contains(base)) pairsLiquid.add(new double[]{kv,bv});
            }
            if (pairs.size()>=minInstr) { rhoDays.add(spearman(pairs)); decileOverlap.add(decileOv(pairs)); }
            if (pairsLiquid.size()>=5) rhoLiquid.add(spearman(pairsLiquid));
        }

        // Annual-mean cross-section: persistent carry ranking (removes daily noise)
        List<double[]> annPairs = new ArrayList<>();
        List<String[]> annTable = new ArrayList<>();
        for (String base : bases) {
            double ks=0,kn=0,bs2=0,bn=0;
            for (long d=d0; d<=d1; d++) {
                double[] kc = kAgg.getOrDefault(base,Map.of()).get(d);
                double[] bc = bAgg.getOrDefault(base,Map.of()).get(d);
                if (kc==null||bc==null||kc[1]==0||bc[1]==0) continue;
                ks+=kc[0]/kc[1]; kn++; bs2+=bc[0]/bc[1]; bn++;
            }
            if (kn>=30 && bn>=30) {
                double kv=ks/kn, bv=bs2/bn;
                annPairs.add(new double[]{kv,bv});
                annTable.add(new String[]{base, String.format("%.1f%%",kv*100), String.format("%.1f%%",bv*100)});
            }
        }

        System.out.println("\n===== RESULTS =====");
        System.out.println("instruments in annual cross-section (>=30 common days): "+annPairs.size());
        System.out.printf("ANNUAL-MEAN Spearman rho = %.3f%n", spearman(annPairs));
        System.out.printf("ANNUAL-MEAN level Pearson r = %.3f%n", pearson(annPairs));
        annTable.sort((x,y)->Double.compare(Double.parseDouble(y[2].replace("%","")),Double.parseDouble(x[2].replace("%",""))));
        System.out.println("base | kraken_ann | binance_ann  (sorted by binance, top12 + bottom6)");
        for (int i=0;i<Math.min(12,annTable.size());i++){ String[] r=annTable.get(i); System.out.printf("  %-10s %8s %8s%n",r[0],r[1],r[2]); }
        System.out.println("  ...");
        for (int i=Math.max(0,annTable.size()-6);i<annTable.size();i++){ String[] r=annTable.get(i); System.out.printf("  %-10s %8s %8s%n",r[0],r[1],r[2]); }
        System.out.println();
        System.out.println("overlap days with >="+minInstr+" common instruments: "+rhoDays.size());
        System.out.printf("mean daily Spearman rho = %.3f (std %.3f)%n", mean(rhoDays), std(rhoDays));
        System.out.printf("frac days rho<0.5 = %.3f%n", frac(rhoDays,0.5));
        System.out.printf("frac days rho<0.8 = %.3f%n", frac(rhoDays,0.8));
        System.out.printf("mean top-decile overlap = %.3f%n", mean(decileOverlap));
        System.out.printf("pooled level Pearson r = %.3f (n=%d instrument-days)%n", pearson(levelPairs), levelPairs.size());
        System.out.printf("liquid-top10 mean daily rho = %.3f (days=%d)%n", mean(rhoLiquid), rhoLiquid.size());
        System.out.println("liquid top by qvol: "+liquidTop);
    }

    static double[] ranks(double[] v){
        int n=v.length; Integer[] idx=new Integer[n];
        for(int i=0;i<n;i++) idx[i]=i;
        Arrays.sort(idx,(x,y)->Double.compare(v[x],v[y]));
        double[] r=new double[n]; int i=0;
        while(i<n){ int j=i; while(j+1<n && v[idx[j+1]]==v[idx[i]]) j++;
            double avg=(i+j)/2.0+1; for(int k=i;k<=j;k++) r[idx[k]]=avg; i=j+1; }
        return r;
    }
    static double spearman(List<double[]> p){
        int n=p.size(); double[] x=new double[n],y=new double[n];
        for(int i=0;i<n;i++){x[i]=p.get(i)[0];y[i]=p.get(i)[1];}
        return pearsonArr(ranks(x),ranks(y));
    }
    static double pearson(List<double[]> p){
        int n=p.size(); double[] x=new double[n],y=new double[n];
        for(int i=0;i<n;i++){x[i]=p.get(i)[0];y[i]=p.get(i)[1];}
        return pearsonArr(x,y);
    }
    static double pearsonArr(double[] x,double[] y){
        int n=x.length; double mx=0,my=0; for(int i=0;i<n;i++){mx+=x[i];my+=y[i];} mx/=n;my/=n;
        double sxy=0,sxx=0,syy=0;
        for(int i=0;i<n;i++){double dx=x[i]-mx,dy=y[i]-my; sxy+=dx*dy; sxx+=dx*dx; syy+=dy*dy;}
        if(sxx==0||syy==0) return Double.NaN;
        return sxy/Math.sqrt(sxx*syy);
    }
    static double decileOv(List<double[]> p){
        int n=p.size(); int k=Math.max(1,(int)Math.ceil(n/10.0));
        Integer[] byK=new Integer[n],byB=new Integer[n];
        for(int i=0;i<n;i++){byK[i]=i;byB[i]=i;}
        Arrays.sort(byK,(x,y)->Double.compare(p.get(y)[0],p.get(x)[0]));
        Arrays.sort(byB,(x,y)->Double.compare(p.get(y)[1],p.get(x)[1]));
        Set<Integer> tK=new HashSet<>(),tB=new HashSet<>();
        for(int i=0;i<k;i++){tK.add(byK[i]);tB.add(byB[i]);}
        int ov=0; for(int i:tK) if(tB.contains(i)) ov++;
        return (double)ov/k;
    }
    static double mean(List<Double> v){ if(v.isEmpty())return Double.NaN; double s=0; for(double x:v)s+=x; return s/v.size(); }
    static double std(List<Double> v){ if(v.size()<2)return Double.NaN; double m=mean(v),s=0; for(double x:v)s+=(x-m)*(x-m); return Math.sqrt(s/(v.size()-1)); }
    static double frac(List<Double> v,double t){ if(v.isEmpty())return Double.NaN; int c=0; for(double x:v) if(x<t)c++; return (double)c/v.size(); }
}
