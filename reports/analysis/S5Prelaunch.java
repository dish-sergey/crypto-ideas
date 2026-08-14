import com.fasterxml.jackson.databind.*;
import java.net.http.*;
import java.net.URI;
import java.time.*;
import java.util.*;

/**
 * Doc 55 §3: two pre-launch checks.
 * §3.1: how many S5 backtest events are tradeable on KRAKEN (universe 274 vs Binance 833) -> real frequency.
 * §3.2: does CURRENT funding (at entry) predict ACCUMULATED 5d funding? (live sees only current rate).
 */
public class S5Prelaunch {
    static final HttpClient HC=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static final ObjectMapper M=new ObjectMapper();
    static final int LEAD=5;

    static JsonNode get(String url) throws Exception { for(int at=0;at<4;at++){ HttpRequest req=HttpRequest.newBuilder(URI.create(url)).header("User-Agent","curl/8.0").timeout(Duration.ofSeconds(50)).GET().build();
        HttpResponse<String> r=HC.send(req,HttpResponse.BodyHandlers.ofString()); if(r.statusCode()==200) return M.readTree(r.body()); if(r.statusCode()==429||r.statusCode()==418){ Thread.sleep(1500L*(at+1)); continue; } throw new RuntimeException("HTTP "+r.statusCode()); } throw new RuntimeException("retries"); }

    public static void main(String[] a) throws Exception {
        JsonNode binfo=get("https://fapi.binance.com/fapi/v1/exchangeInfo"); Set<String> bperp=new HashSet<>();
        for(JsonNode n:binfo.get("symbols")) if("PERPETUAL".equals(n.path("contractType").asText())&&"USDT".equals(n.path("quoteAsset").asText())) bperp.add(n.path("baseAsset").asText().toUpperCase());
        JsonNode kins=get("https://futures.kraken.com/derivatives/api/v3/instruments"); Set<String> kperp=new HashSet<>();
        for(JsonNode n:kins.get("instruments")){ String s=n.path("symbol").asText().toUpperCase(); if(n.path("tradeable").asBoolean(false)&&s.startsWith("PF_")&&s.endsWith("USD")){ String b=s.substring(3,s.length()-3); if(b.equals("XBT"))b="BTC"; kperp.add(b); } }
        System.out.println("Binance perp bases: "+bperp.size()+" | Kraken tradeable bases: "+kperp.size());
        JsonNode cg=get("https://api.coingecko.com/api/v3/coins/list"); Map<String,String> gid=new HashMap<>(); for(JsonNode n:cg) gid.put(n.path("id").asText(),n.path("symbol").asText().toUpperCase());

        // events from cache (base, day, pct)
        java.nio.file.Path cache=java.nio.file.Path.of(System.getenv("TEMP")==null?"/tmp":System.getenv("TEMP"),"s5cache");
        List<Object[]> ev=new ArrayList<>(); Set<String> bases=new TreeSet<>();
        for(java.nio.file.Path cf:(Iterable<java.nio.file.Path>)java.nio.file.Files.list(cache)::iterator){ try{
            JsonNode e=M.readTree(cf.toFile()); JsonNode meta=e.path("metadata"); String tok=meta.path("token").asText(""); String gecko=tok.startsWith("coingecko:")?tok.substring(10):e.path("gecko_id").asText(""); String sym=gid.getOrDefault(gecko,""); if(sym.isEmpty()||!bperp.contains(sym)) continue;
            TreeMap<Long,Double> total=new TreeMap<>(); for(JsonNode c:e.path("documentedData").path("data")) for(JsonNode p:c.path("data")){ long ts=p.path("timestamp").asLong(); total.merge(ts,p.path("unlocked").asDouble(0),Double::sum);} if(total.isEmpty()) continue;
            JsonNode evs=meta.path("events"); if(evs.isMissingNode()||!evs.isArray()) evs=e.path("events");
            for(JsonNode x:evs){ if(!"cliff".equals(x.path("unlockType").asText())) continue; JsonNode t=x.path("noOfTokens"); if(!t.isArray()||t.size()==0) continue; double amt=t.get(0).asDouble(0); if(amt<=0) continue; long ts=x.path("timestamp").asLong(); Long fk=total.floorKey(ts); if(fk==null) continue; double circ=total.get(fk); if(circ<=0) continue; double pct=amt/circ; if(pct<0.03) continue; ev.add(new Object[]{sym,ts/86400}); bases.add(sym); }
        }catch(Exception ex){} }

        // §3.1 Kraken availability
        System.out.println("\n===== §3.1: доступность вселенной на Kraken =====");
        int onK=0; Set<String> kbases=new TreeSet<>(); for(Object[] e:ev){ String b=(String)e[0]; if(kperp.contains(b)){ onK++; kbases.add(b);} }
        System.out.printf("событий бэктеста (≥3%%circ, Binance-перп): %d | из них торгуются на Kraken: %d (%.0f%%)%n", ev.size(), onK, 100.0*onK/Math.max(1,ev.size()));
        System.out.printf("уникальных токенов: %d, из них на Kraken: %d%n", bases.size(), kbases.size());
        // frequency estimate: events on Kraken per month
        long minD=Long.MAX_VALUE,maxD=0; for(Object[] e:ev){ long d=(long)e[1]; if(kperp.contains((String)e[0])){ minD=Math.min(minD,d); maxD=Math.max(maxD,d);} }
        double months=(maxD-minD)/30.44; System.out.printf("частота Kraken-событий: %.1f/мес (%d за %.1f мес) — против заявленных ~10/мес%n", onK/Math.max(1,months), onK, months);

        // §3.2 predictive funding — needs Binance funding per token
        System.out.println("\n===== §3.2: предсказуемость funding на 5 дней =====");
        Map<String,TreeMap<Long,Double>> fund=new HashMap<>(); long from=Instant.parse("2020-01-01T00:00:00Z").toEpochMilli(); int fi=0;
        for(String b:bases){ try{ TreeMap<Long,Double> fm=new TreeMap<>(); long st=from; for(int pg=0;pg<8;pg++){ JsonNode fr=get("https://fapi.binance.com/fapi/v1/fundingRate?symbol="+b+"USDT&startTime="+st+"&limit=1000"); if(!fr.isArray()||fr.size()==0) break; long last=st; for(JsonNode row:fr){ long ts=row.path("fundingTime").asLong(); fm.merge(ts/86400000L,row.path("fundingRate").asDouble(),Double::sum); last=ts; } if(fr.size()<1000) break; st=last+1; } fund.put(b,fm);
        }catch(Exception ex){} if(++fi%40==0) System.out.println("  funding "+fi+"/"+bases.size()); Thread.sleep(20); }

        List<double[]> pairs=new ArrayList<>(); int agree=0,tot=0;
        for(Object[] e:ev){ String b=(String)e[0]; long day=(long)e[1]; TreeMap<Long,Double> fm=fund.get(b); if(fm==null) continue;
            Double cur=fm.get(day-LEAD); if(cur==null) continue; // funding on entry day (current rate proxy)
            double accum=0; boolean full=true; for(long d=day-LEAD; d<day; d++){ Double f=fm.get(d); if(f==null){full=false;break;} accum+=f; } if(!full) continue;
            double pred5=cur*LEAD; pairs.add(new double[]{cur,accum});
            boolean actualCheap = accum >= -0.015, predCheap = pred5 >= -0.015; if(actualCheap==predCheap) agree++; tot++; }
        System.out.printf("пар (funding вход + накопл.5д): %d%n", pairs.size());
        System.out.printf("корреляция current↔accumulated-5d: %.3f%n", pearson(pairs));
        System.out.printf("совпадение решения фильтра (дорогой/дешёвый) по current×5 против факт.5д: %.0f%% (%d/%d)%n", 100.0*agree/Math.max(1,tot), agree, tot);
        System.out.println("Чтение: высокая корр + совпадение >~85%% => текущая ставка годна как прокси фильтра в live");
    }

    static double pearson(List<double[]> p){ int n=p.size(); if(n<3) return 0; double mx=0,my=0; for(double[] x:p){mx+=x[0];my+=x[1];} mx/=n;my/=n; double sxy=0,sxx=0,syy=0; for(double[] x:p){double dx=x[0]-mx,dy=x[1]-my; sxy+=dx*dy; sxx+=dx*dx; syy+=dy*dy;} return (sxx==0||syy==0)?0:sxy/Math.sqrt(sxx*syy); }
}
