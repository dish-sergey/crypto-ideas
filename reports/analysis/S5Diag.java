import com.fasterxml.jackson.databind.*;
import java.net.http.*;
import java.net.URI;
import java.time.*;
import java.util.*;

/**
 * Doc 56: two DIAGNOSTIC measurements (no protocol change).
 * A (§1): premium by unlock-size bin (3-5/5-10/10-20/20%+), n shown.
 * B (§2): decompose short return into event (residual) vs market beta; residual = absRet + beta*BTC5dRet;
 *         split by SMA200 detector state (BULL/BEAR) at entry. Does the tail cluster in BULL?
 */
public class S5Diag {
    static final HttpClient HC=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static final ObjectMapper M=new ObjectMapper();
    static final long DAY=86400000L; static final int LEAD=5; static final double COST=0.0019;

    static JsonNode get(String url) throws Exception { for(int at=0;at<4;at++){ HttpRequest req=HttpRequest.newBuilder(URI.create(url)).header("User-Agent","curl/8.0").timeout(Duration.ofSeconds(50)).GET().build();
        HttpResponse<String> r=HC.send(req,HttpResponse.BodyHandlers.ofString()); if(r.statusCode()==200) return M.readTree(r.body()); if(r.statusCode()==429||r.statusCode()==418){ Thread.sleep(1500L*(at+1)); continue; } throw new RuntimeException("HTTP "+r.statusCode()); } throw new RuntimeException("retries"); }

    record Ev(String base,long day,double pct,double fund5){}
    static Map<String,TreeMap<Long,double[]>> px=new HashMap<>(); static Map<String,TreeMap<Long,Double>> fund=new HashMap<>();
    static TreeMap<Long,Double> btc=new TreeMap<>();

    public static void main(String[] a) throws Exception {
        JsonNode binfo=get("https://fapi.binance.com/fapi/v1/exchangeInfo"); Set<String> perp=new HashSet<>();
        for(JsonNode n:binfo.get("symbols")) if("PERPETUAL".equals(n.path("contractType").asText())&&"USDT".equals(n.path("quoteAsset").asText())) perp.add(n.path("baseAsset").asText().toUpperCase());
        JsonNode cg=get("https://api.coingecko.com/api/v3/coins/list"); Map<String,String> gid=new HashMap<>(); for(JsonNode n:cg) gid.put(n.path("id").asText(),n.path("symbol").asText().toUpperCase());
        java.nio.file.Path cache=java.nio.file.Path.of(System.getenv("TEMP")==null?"/tmp":System.getenv("TEMP"),"s5cache");
        List<Object[]> raw=new ArrayList<>(); Set<String> bases=new TreeSet<>();
        for(java.nio.file.Path cf:(Iterable<java.nio.file.Path>)java.nio.file.Files.list(cache)::iterator){ try{
            JsonNode e=M.readTree(cf.toFile()); JsonNode meta=e.path("metadata"); String tok=meta.path("token").asText(""); String gecko=tok.startsWith("coingecko:")?tok.substring(10):e.path("gecko_id").asText(""); String sym=gid.getOrDefault(gecko,""); if(sym.isEmpty()||!perp.contains(sym)) continue;
            TreeMap<Long,Double> total=new TreeMap<>(); for(JsonNode c:e.path("documentedData").path("data")) for(JsonNode p:c.path("data")){ long ts=p.path("timestamp").asLong(); total.merge(ts,p.path("unlocked").asDouble(0),Double::sum);} if(total.isEmpty()) continue;
            JsonNode evs=meta.path("events"); if(evs.isMissingNode()||!evs.isArray()) evs=e.path("events");
            for(JsonNode x:evs){ if(!"cliff".equals(x.path("unlockType").asText())) continue; JsonNode t=x.path("noOfTokens"); if(!t.isArray()||t.size()==0) continue; double amt=t.get(0).asDouble(0); if(amt<=0) continue; long ts=x.path("timestamp").asLong(); Long fk=total.floorKey(ts); if(fk==null) continue; double circ=total.get(fk); if(circ<=0) continue; double pct=amt/circ; if(pct<0.03) continue; raw.add(new Object[]{sym,ts/86400,Math.min(pct,1)}); bases.add(sym); }
        }catch(Exception ex){} }
        long from=Instant.parse("2019-06-01T00:00:00Z").toEpochMilli(); int fi=0;
        // BTC first
        { TreeMap<Long,double[]> m=fetchPx("BTC"); for(var en:m.entrySet()) btc.put(en.getKey(),en.getValue()[0]); }
        for(String b:bases){ try{ px.put(b,fetchPx(b));
            TreeMap<Long,Double> fm=new TreeMap<>(); long st=from; for(int pg=0;pg<8;pg++){ JsonNode fr=get("https://fapi.binance.com/fapi/v1/fundingRate?symbol="+b+"USDT&startTime="+st+"&limit=1000"); if(!fr.isArray()||fr.size()==0) break; long last=st; for(JsonNode row:fr){ long ts=row.path("fundingTime").asLong(); fm.merge(ts/DAY,row.path("fundingRate").asDouble(),Double::sum); last=ts; } if(fr.size()<1000) break; st=last+1; } fund.put(b,fm);
        }catch(Exception ex){} if(++fi%40==0) System.out.println("  "+fi+"/"+bases.size()); Thread.sleep(20); }

        List<Ev> EV=new ArrayList<>(); for(Object[] r:raw){ String b=(String)r[0]; long day=(long)r[1]; TreeMap<Long,Double> fm=fund.get(b); double f5=0; if(fm!=null) for(long d=day-LEAD; d<day; d++){ Double f=fm.get(d); if(f!=null) f5+=f; } EV.add(new Ev(b,day,(double)r[2],f5)); }

        // build trades: [pct, absRet, btc5d, beta, residual, bull(1/0)]
        List<double[]> tr=new ArrayList<>();
        for(Ev ev:EV){ if(ev.fund5< -0.015) continue; TreeMap<Long,double[]> m=px.get(ev.base); if(m==null) continue; double[] e0=near(m,ev.day-5),e1=near(m,ev.day); if(e0==null||e1==null||e0[0]<=0) continue;
            double entry=e0[0]; boolean hit=false; for(long d=ev.day-4; d<=ev.day; d++){ double[] x=m.get(d); if(x!=null&&x[1]>=entry*1.30){ hit=true; break; } }
            double absRet=(hit? -0.31 : (entry-e1[0])/entry)+ev.fund5-COST;
            Double bt0=nearB(ev.day-5),bt1=nearB(ev.day); if(bt0==null||bt1==null||bt0<=0) continue; double btc5=(bt1-bt0)/bt0;
            double beta=beta(m,ev.day); double residual=absRet+beta*btc5; // short wins from market fall
            Double sma=sma200(ev.day-5); if(sma==null) continue; boolean bull= bt0>sma;
            tr.add(new double[]{ev.pct, absRet, btc5, beta, residual, bull?1:0}); }
        System.out.println("trades: "+tr.size());

        // ===== A: by unlock size =====
        System.out.println("\n===== A: премия против размера разлока (§1) =====");
        double[][] bins={{0.03,0.05},{0.05,0.10},{0.10,0.20},{0.20,1.01}}; String[] bl={"3–5%","5–10%","10–20%","20%+"};
        for(int i=0;i<bins.length;i++){ List<Double> r=new ArrayList<>(); for(double[] t:tr) if(t[0]>=bins[i][0]&&t[0]<bins[i][1]) r.add(t[1]);
            Collections.sort(r); System.out.printf("  %-7s n=%-4d absRet=%+.2f%%  медиана=%+.2f%%  %%>0=%.0f%%  худшие10%%=%+.1f%%%n", bl[i], r.size(), mean(r)*100, median(r)*100, 100.0*fracPos(r), worst10(r)*100); }

        // ===== B: decomposition + detector state =====
        System.out.println("\n===== B: разложение премии событие vs рынок (§2) =====");
        List<Double> abs=col(tr,1), res=col(tr,4);
        System.out.printf("сырая absRet=%+.2f%%  остаточная (за вычетом рынка)=%+.2f%%  => рыночная компонента=%+.2f пп%n", mean(abs)*100, mean(res)*100, (mean(abs)-mean(res))*100);
        System.out.println("по состоянию детектора SMA200 на входе:");
        for(int st=1; st>=0; st--){ List<Double> ra=new ArrayList<>(),rr=new ArrayList<>(); for(double[] t:tr) if((int)t[5]==st){ ra.add(t[1]); rr.add(t[4]); }
            List<Double> sra=new ArrayList<>(ra); Collections.sort(sra);
            System.out.printf("  %-5s n=%-4d сырая=%+.2f%%  остаточная=%+.2f%%  худшие10%%=%+.1f%%%n", st==1?"BULL":"BEAR", ra.size(), mean(ra)*100, mean(rr)*100, worst10(sra)*100); }
        // tail concentration
        List<double[]> byRet=new ArrayList<>(tr); byRet.sort((x,y)->Double.compare(x[1],y[1])); int k=Math.max(1,byRet.size()/10); int bullTail=0; for(int i=0;i<k;i++) if((int)byRet.get(i)[5]==1) bullTail++;
        System.out.printf("худшие 10%% сделок: доля в BULL = %.0f%% (%d/%d)%n", 100.0*bullTail/k, bullTail, k);
        System.out.println("\n(диагностика, протокол НЕ меняется; варианты гейт/хедж/ничего — отдельным решением после микро-live)");
    }

    static TreeMap<Long,double[]> fetchPx(String b) throws Exception { long from=Instant.parse("2019-06-01T00:00:00Z").toEpochMilli(); TreeMap<Long,double[]> m=new TreeMap<>(); long st=from;
        for(int pg=0;pg<5;pg++){ JsonNode kl=get("https://fapi.binance.com/fapi/v1/klines?symbol="+b+"USDT&interval=1d&startTime="+st+"&limit=1500"); if(!kl.isArray()||kl.size()==0) break; long last=st; for(JsonNode c:kl){ long ot=c.get(0).asLong(); m.put(ot/DAY,new double[]{Double.parseDouble(c.get(4).asText()),Double.parseDouble(c.get(2).asText())}); last=ot; } if(kl.size()<1500) break; st=last+DAY; } return m; }
    static double beta(TreeMap<Long,double[]> m,long day){ List<double[]> pr=new ArrayList<>(); Double pt=null,pb=null;
        for(long d=day-95; d<day-5; d++){ double[] xt=m.get(d); Double xb=btc.get(d); if(xt!=null&&xb!=null){ if(pt!=null&&pb!=null&&pt>0&&pb>0) pr.add(new double[]{xt[0]/pt-1, xb/pb-1}); pt=xt[0]; pb=xb; } else { pt=xt!=null?xt[0]:null; pb=xb; } }
        if(pr.size()<30) return 1; double mb=0; for(double[] p:pr) mb+=p[1]; mb/=pr.size(); double mt=0; for(double[] p:pr) mt+=p[0]; mt/=pr.size(); double cov=0,varb=0; for(double[] p:pr){ cov+=(p[0]-mt)*(p[1]-mb); varb+=(p[1]-mb)*(p[1]-mb); } return varb<=0?1:cov/varb; }
    static Double sma200(long day){ double s=0; int n=0; for(long d=day-200; d<day; d++){ Double b=btc.get(d); if(b!=null){ s+=b; n++; } } return n<100?null:s/n; }
    static Double nearB(long d){ Double x=btc.get(d); if(x!=null) return x; Long f=btc.floorKey(d); return (f!=null&&f>=d-3)?btc.get(f):null; }
    static double[] near(TreeMap<Long,double[]> m,long d){ double[] x=m.get(d); if(x!=null) return x; Long f=m.floorKey(d); if(f!=null&&f>=d-3) return m.get(f); Long g=m.ceilingKey(d); if(g!=null&&g<=d+3) return m.get(g); return null; }
    static List<Double> col(List<double[]> t,int i){ List<Double> o=new ArrayList<>(); for(double[] x:t) o.add(x[i]); return o; }
    static double mean(List<Double> v){ if(v.isEmpty())return 0; double s=0; for(double x:v)s+=x; return s/v.size(); }
    static double median(List<Double> v){ if(v.isEmpty())return 0; List<Double> s=new ArrayList<>(v); Collections.sort(s); int n=s.size(); return n%2==1?s.get(n/2):(s.get(n/2-1)+s.get(n/2))/2; }
    static double fracPos(List<Double> v){ if(v.isEmpty())return 0; int c=0; for(double x:v) if(x>0)c++; return (double)c/v.size(); }
    static double worst10(List<Double> v){ if(v.isEmpty())return 0; List<Double> s=new ArrayList<>(v); Collections.sort(s); int k=Math.max(1,s.size()/10); double su=0; for(int i=0;i<k;i++) su+=s.get(i); return su/k; }
}
