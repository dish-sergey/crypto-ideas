import com.fasterxml.jackson.databind.*;
import java.net.http.*;
import java.net.URI;
import java.time.*;
import java.util.*;

/**
 * Doc 53: three clarifications before paper + min-order check.
 * A (§3): stop correlation — same-day multi-stops, max simultaneous, rolling-5d stop distribution.
 * B (§4): entry-window cliff — 4d premium + avg price path by day -7..-1 (mechanism = anticipation check).
 * C (§5): stop/restart rule backtest on history (pause if rolling-20<0; restart rolling-20>+1% & 30d & half-size).
 * MinOrder (§6.3): does $45 position clear Binance min notional/lot on S5 tokens.
 */
public class S5Paper {
    static final HttpClient HC=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static final ObjectMapper M=new ObjectMapper();
    static final long DAY=86400000L; static final int LEAD=5; static final double SLIP=0.01, COST=0.0019;

    static JsonNode get(String url) throws Exception { for(int at=0;at<4;at++){ HttpRequest req=HttpRequest.newBuilder(URI.create(url)).header("User-Agent","curl/8.0").timeout(Duration.ofSeconds(50)).GET().build();
        HttpResponse<String> r=HC.send(req,HttpResponse.BodyHandlers.ofString()); if(r.statusCode()==200) return M.readTree(r.body()); if(r.statusCode()==429||r.statusCode()==418){ Thread.sleep(1500L*(at+1)); continue; } throw new RuntimeException("HTTP "+r.statusCode()); } throw new RuntimeException("retries"); }

    record Ev(String base,long day,double pct,double fund5){}
    static Map<String,TreeMap<Long,double[]>> px=new HashMap<>(); static Map<String,TreeMap<Long,Double>> fund=new HashMap<>();
    static List<Ev> EVENTS=new ArrayList<>();
    static Map<String,double[]> minOrder=new HashMap<>(); // base -> [minNotional, minQty]

    public static void main(String[] a) throws Exception {
        JsonNode binfo=get("https://fapi.binance.com/fapi/v1/exchangeInfo"); Set<String> perp=new HashSet<>();
        for(JsonNode n:binfo.get("symbols")){ if(!"PERPETUAL".equals(n.path("contractType").asText())||!"USDT".equals(n.path("quoteAsset").asText())) continue; String base=n.path("baseAsset").asText().toUpperCase(); perp.add(base);
            double mn=5, mq=0; for(JsonNode f:n.path("filters")){ String ty=f.path("filterType").asText(); if(ty.equals("MIN_NOTIONAL")) mn=f.path("notional").asDouble(5); if(ty.equals("LOT_SIZE")) mq=f.path("minQty").asDouble(0); } minOrder.put(base,new double[]{mn,mq}); }
        JsonNode cg=get("https://api.coingecko.com/api/v3/coins/list"); Map<String,String> gid=new HashMap<>(); for(JsonNode n:cg) gid.put(n.path("id").asText(),n.path("symbol").asText().toUpperCase());
        java.nio.file.Path cache=java.nio.file.Path.of(System.getenv("TEMP")==null?"/tmp":System.getenv("TEMP"),"s5cache");
        List<Object[]> raw=new ArrayList<>(); Set<String> bases=new TreeSet<>();
        for(java.nio.file.Path cf:(Iterable<java.nio.file.Path>)java.nio.file.Files.list(cache)::iterator){ try{
            JsonNode e=M.readTree(cf.toFile()); JsonNode meta=e.path("metadata"); String tok=meta.path("token").asText(""); String gecko=tok.startsWith("coingecko:")?tok.substring(10):e.path("gecko_id").asText(""); String sym=gid.getOrDefault(gecko,""); if(sym.isEmpty()||!perp.contains(sym)) continue;
            TreeMap<Long,Double> total=new TreeMap<>(); for(JsonNode c:e.path("documentedData").path("data")) for(JsonNode p:c.path("data")){ long ts=p.path("timestamp").asLong(); total.merge(ts,p.path("unlocked").asDouble(0),Double::sum);} if(total.isEmpty()) continue;
            JsonNode evs=meta.path("events"); if(evs.isMissingNode()||!evs.isArray()) evs=e.path("events");
            for(JsonNode ev:evs){ if(!"cliff".equals(ev.path("unlockType").asText())) continue; JsonNode t=ev.path("noOfTokens"); if(!t.isArray()||t.size()==0) continue; double amt=t.get(0).asDouble(0); if(amt<=0) continue; long ts=ev.path("timestamp").asLong(); Long fk=total.floorKey(ts); if(fk==null) continue; double circ=total.get(fk); if(circ<=0) continue; double pct=amt/circ; if(pct<0.03) continue; raw.add(new Object[]{sym,ts/86400,Math.min(pct,1)}); bases.add(sym); }
        }catch(Exception ex){} }
        long from=Instant.parse("2020-01-01T00:00:00Z").toEpochMilli(); int fi=0;
        for(String b:bases){ try{ TreeMap<Long,double[]> m=new TreeMap<>(); long st=from;
            for(int pg=0;pg<4;pg++){ JsonNode kl=get("https://fapi.binance.com/fapi/v1/klines?symbol="+b+"USDT&interval=1d&startTime="+st+"&limit=1500"); if(!kl.isArray()||kl.size()==0) break; long last=st; for(JsonNode c:kl){ long ot=c.get(0).asLong(); m.put(ot/DAY,new double[]{Double.parseDouble(c.get(4).asText()),Double.parseDouble(c.get(2).asText())}); last=ot; } if(kl.size()<1500) break; st=last+DAY; } if(m.size()>50) px.put(b,m);
            TreeMap<Long,Double> fm=new TreeMap<>(); st=from; for(int pg=0;pg<8;pg++){ JsonNode fr=get("https://fapi.binance.com/fapi/v1/fundingRate?symbol="+b+"USDT&startTime="+st+"&limit=1000"); if(!fr.isArray()||fr.size()==0) break; long last=st; for(JsonNode row:fr){ long ts=row.path("fundingTime").asLong(); fm.merge(ts/DAY,row.path("fundingRate").asDouble(),Double::sum); last=ts; } if(fr.size()<1000) break; st=last+1; } fund.put(b,fm);
        }catch(Exception ex){} if(++fi%40==0) System.out.println("  prices "+fi+"/"+bases.size()); Thread.sleep(20); }
        for(Object[] r:raw){ String b=(String)r[0]; long day=(long)r[1]; TreeMap<Long,Double> fm=fund.get(b); double f5=0; if(fm!=null) for(long d=day-LEAD; d<day; d++){ Double f=fm.get(d); if(f!=null) f5+=f; } EVENTS.add(new Ev(b,day,(double)r[2],f5)); }
        System.out.println("EVENTS: "+EVENTS.size());

        // traded set (skip expensive), with day + stopped flag + absRet, window 5
        List<double[]> tr=new ArrayList<>(); // day, absRet, stopped(1/0)
        for(Ev ev:EVENTS){ if(ev.fund5< -0.015) continue; TreeMap<Long,double[]> m=px.get(ev.base); if(m==null) continue; double[] e0=near(m,ev.day-5),e1=near(m,ev.day); if(e0==null||e1==null||e0[0]<=0) continue;
            double entry=e0[0]; boolean hit=false; for(long d=ev.day-4; d<=ev.day; d++){ double[] x=m.get(d); if(x!=null&&x[1]>=entry*1.30){ hit=true; break; } }
            double abs=(hit? -0.31 : (entry-e1[0])/entry)+ev.fund5-COST; tr.add(new double[]{ev.day,abs,hit?1:0}); }
        tr.sort((x,y)->Long.compare((long)x[0],(long)y[0]));
        System.out.println("traded: "+tr.size());

        // ---- A: stop correlation ----
        System.out.println("\n===== A: корреляция стопов (§3) =====");
        TreeMap<Long,Integer> stopsByDay=new TreeMap<>(); for(double[] t:tr) if(t[2]>0) stopsByDay.merge((long)t[0],1,Integer::sum);
        int days2=0,days5=0,maxDay=0; for(int v:stopsByDay.values()){ if(v>=2) days2++; if(v>=5) days5++; maxDay=Math.max(maxDay,v); }
        int maxWin5=0; List<Long> sd=new ArrayList<>(stopsByDay.keySet());
        for(long d0:sd){ int c=0; for(var en:stopsByDay.tailMap(d0).entrySet()){ if(en.getKey()>d0+5) break; c+=en.getValue(); } maxWin5=Math.max(maxWin5,c); }
        int totalStops=0; for(int v:stopsByDay.values()) totalStops+=v;
        System.out.printf("всего стопов=%d; дней с ≥2 стопами=%d; с ≥5=%d; макс стопов в один день=%d; макс в окно 5д=%d%n", totalStops, days2, days5, maxDay, maxWin5);
        System.out.println(days5>0||maxWin5>=5?"=> МАССОВЫЕ стопы случались — сценарий реален, лимит обоснован":"=> стопы разрознены — лимит 4.5% консервативен");

        // ---- B: window cliff + price path ----
        System.out.println("\n===== B: обрыв по окну входа (§4) =====");
        for(int w:new int[]{3,4,5,7}){ List<Double> p=new ArrayList<>();
            for(Ev ev:EVENTS){ if(ev.fund5< -0.015) continue; TreeMap<Long,double[]> m=px.get(ev.base); if(m==null) continue; double[] e0=near(m,ev.day-w),e1=near(m,ev.day); if(e0==null||e1==null||e0[0]<=0) continue; boolean hit=false; for(long d=ev.day-w+1; d<=ev.day; d++){ double[] x=m.get(d); if(x!=null&&x[1]>=e0[0]*1.30){ hit=true; break; } } p.add((hit?-0.31:(e0[0]-e1[0])/e0[0])+ev.fund5-COST); }
            System.out.printf("  окно %dд: премия=%+.2f%% (n=%d)%n", w, mean(p)*100, p.size()); }
        System.out.println("средний ход цены по дням до разлока (норм. к дню −7):");
        for(int off=-7; off<=0; off++){ List<Double> pp=new ArrayList<>();
            for(Ev ev:EVENTS){ TreeMap<Long,double[]> m=px.get(ev.base); if(m==null) continue; double[] b7=near(m,ev.day-7),cur=near(m,ev.day+off); if(b7==null||cur==null||b7[0]<=0) continue; pp.add(cur[0]/b7[0]-1); }
            System.out.printf("  день %+d: %+.2f%%%n", off, mean(pp)*100); }
        System.out.println("(если основное падение в дни −5…−3 => подтверждает предвосхищение рынком)");

        // ---- C: stop/restart rule backtest ----
        System.out.println("\n===== C: правило остановки/перезапуска на истории (§5.2) =====");
        double eqRule=1, eqAll=1; boolean paused=false; long pausedDay=0; int halfLeft=0; LinkedList<Double> roll=new LinkedList<>();
        int skipped=0, traded=0;
        for(double[] t:tr){ long day=(long)t[0]; double r=t[1]; eqAll*=(1+r);
            roll.add(r); if(roll.size()>20) roll.removeFirst(); double rmean=roll.size()>=20?mean(new ArrayList<>(roll)):1;
            if(!paused){ double size=halfLeft>0?0.5:1.0; eqRule*=(1+r*size); traded++; if(halfLeft>0) halfLeft--;
                if(roll.size()>=20 && rmean<0){ paused=true; pausedDay=day; } }
            else { skipped++; // observe only
                if(roll.size()>=20 && rmean>0.01 && day-pausedDay>=30){ paused=false; halfLeft=20; } } }
        System.out.printf("итог БЕЗ правила (sleeve, компаунд absRet)=%.2fx | С правилом=%.2fx | пропущено сделок=%d, торговано=%d%n", eqAll, eqRule, skipped, traded);
        System.out.println(eqRule>=eqAll?"=> правило улучшает/не хуже — ловит деградацию":"=> правило ХУЖЕ — ловит нормальную дисперсию, порог пересмотреть до запуска");

        // ---- MinOrder $45 ----
        System.out.println("\n===== §6.3: минимальный ордер $45 =====");
        int pass=0,fail=0; List<String> failList=new ArrayList<>();
        for(String b:bases){ TreeMap<Long,double[]> m=px.get(b); double[] mo=minOrder.get(b); if(m==null||mo==null) continue; double price=m.lastEntry().getValue()[0]; if(price<=0) continue;
            boolean ok = 45.0>=mo[0] && (mo[1]<=0 || mo[1]*price<=45.0); if(ok) pass++; else { fail++; if(failList.size()<15) failList.add(b); } }
        System.out.printf("позиция $45 проходит min-ордер: %d/%d токенов%n", pass, pass+fail);
        if(fail>0) System.out.println("  не проходят (примеры): "+failList);
        System.out.println(fail>(pass+fail)*0.2?"=> значимая часть не проходит — поднять paper-капитал до $2-3k":"=> проходит на большинстве — $1k paper ок");
    }

    static double[] near(TreeMap<Long,double[]> m,long d){ double[] x=m.get(d); if(x!=null) return x; Long f=m.floorKey(d); if(f!=null&&f>=d-3) return m.get(f); Long g=m.ceilingKey(d); if(g!=null&&g<=d+3) return m.get(g); return null; }
    static double mean(List<Double> v){ if(v.isEmpty())return 0; double s=0; for(double x:v)s+=x; return s/v.size(); }
}
