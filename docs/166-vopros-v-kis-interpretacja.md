# 166. Запрос в KIS: текст на польском, куда и как подавать

Вспомогательный документ к `165-...-v2.md`, часть V-bis.
⚠️ Я не налоговый консультант. Ниже — заготовка, собранная из открытых источников;
номера статей и суммы стоит перепроверить перед отправкой.

---

# Часть I. Сначала про залог в USDC — что он меняет

**Да, USDC принимается как залог** в мультизалоговом кошельке Kraken Derivatives.

| залог | дисконт (haircut) | комиссия конверсии |
|---|---:|---:|
| USD | 0 % | 0 % |
| EUR | 0 % | 0 % |
| **USDC** | **0.50 %** | **0 %** |
| USDT | 0.50 % | 0 % |
| BTC | 1 % | 0.20 % |

Дополнительно: прибыль/убыток в мультизалоговом кошельке по умолчанию **книжится в
USD**, но валюту расчёта результата **можно вручную переключить** на другую
залоговую валюту — в том числе на USDC.

## Что это меняет на самом деле

**Операционно — заметно, и в плюс:**

- **исчезают фиатные рельсы.** Деньги ходят Revolut ↔ Kraken как USDC, а не
  банковским переводом: быстрее, работает в выходные, и при масштабе это перестаёт
  быть мелочью, потому что залог придётся подкачивать;
- **исчезает EUR/USD-экспозиция.** Сейчас залог в EUR, а контракт в USD — на счёте
  висит валютная позиция, которую никто не хеджирует. USDC ближе к USD, чем EUR;
- **залог совпадает по валюте со спотовой ногой.** Весь ваш спот номинирован в
  USDC. Если USDC отклонится от доллара, обе части книги поедут вместе, а не
  врозь;
- цена: дисконт 0.5 % против 0 % у EUR. На марже в 1 % от позиции это
  округление, а не расход.

**Налогово — скорее всего ничего.** По разъяснению Минфина, на которое ссылается
Kryptoprawo, классификация зависит от того, **подпадает ли сам инструмент под
определение финансового инструмента**, а не от валюты залога, расчёта или площадки.
Контракт остаётся `PF_XBTUSD`, то есть USD-номинированным.

⚠️ **Но это не точно, и именно поэтому стоит спросить.** Divly отмечает, что
фьючерсы, рассчитываемые в криптовалюте, облагаются только при выходе в фиат. Если
переключить валюту расчёта результата на USDC, ваш случай оказывается ровно на
границе. Поэтому в описании для KIS обе детали — залог в USDC и расчёт в USDC —
указаны явно, и про них задан отдельный вопрос.

---

# Часть II. 🔑 Тактика: подавать как «zdarzenie przyszłe», и подавать сейчас

Это важнее самого текста.

| | |
|---|---|
| интерпретация получена **ДО** события | защита полная: ни доначисления, ни штрафа, ни уголовной ответственности |
| интерпретация получена **ПОСЛЕ** события | защита только от штрафа и уголовки — **налог доначислят** |

**Хедж вы живьём ещё не запускали** — он пока только наложен на траекторию в
расчёте. Значит конструкция честно описывается как **`zdarzenie przyszłe`**
(будущее событие), и это даёт полную защиту. Через полгода, когда хедж поработает,
такой возможности уже не будет.

И второе: ответ идёт **до трёх месяцев**, а если KIS не уложится — **ваша позиция
считается правильной автоматически**. Поэтому в разделе «własne stanowisko» надо
писать тот ответ, который вам выгоден, а не тот, который кажется вероятным.

⚠️ Честно про шансы по вопросу 1: позиция «перп — это виртуальная валюта»
**слабее**. Площадка Kraken в ЕОГ работает как MTF, а крипто-деривативы по MiFID II
считаются финансовыми инструментами. Скорее всего KIS ответит «pochodny instrument
finansowy». Но отрицательный ответ ничего вам не стоит — ни штрафа, ни последствий,
— а положительный снимает всю поправку V-bis.1. Плюс остаётся опция трёх месяцев.

---

# Часть III. Текст запроса

Ниже — три блока формы ORD-IN: описание, вопросы, собственная позиция.
Польский текст, под каждым блоком перевод.

## G. OPIS ZDARZENIA PRZYSZŁEGO — описание будущего события

```
Wnioskodawca jest osobą fizyczną, polskim rezydentem podatkowym,
nieprowadzącą działalności gospodarczej.

Wnioskodawca zamierza prowadzić działalność inwestycyjną na rynku
kryptoaktywów przy użyciu własnego programu komputerowego, w dwóch
powiązanych ze sobą częściach:

1. Na giełdzie kryptoaktywów Wnioskodawca będzie kupował i sprzedawał
bitcoina (BTC), ethereum (ETH) oraz solanę (SOL) wyłącznie w parach
z USDC. Wnioskodawca nie będzie wymieniał tych kryptoaktywów na prawny
środek płatniczy ani regulował nimi zobowiązań. USDC jest tokenem
o wartości powiązanej z dolarem amerykańskim, emitowanym przez Circle
Internet Financial i notyfikowanym w Unii Europejskiej jako token
będący e-pieniądzem (EMT) w rozumieniu rozporządzenia MiCA.

2. Równolegle, wyłącznie w celu ograniczenia ryzyka zmiany ceny
posiadanych kryptoaktywów, Wnioskodawca będzie zajmował przeciwstawne
(krótkie) pozycje na bezterminowych kontraktach terminowych typu
perpetual futures (PF_XBTUSD, PF_ETHUSD, PF_SOLUSD) na platformie
Kraken, prowadzonej dla klientów z Europejskiego Obszaru
Gospodarczego. Kontrakty te nie mają terminu wygaśnięcia, są
denominowane w dolarze amerykańskim i rozliczane wyłącznie pieniężnie,
bez dostawy instrumentu bazowego. Wnioskodawca zamierza wnieść
zabezpieczenie (depozyt zabezpieczający) tych pozycji w USDC oraz
ustawić walutę rozliczenia wyniku na USDC.

Wielkość pozycji na kontraktach będzie w każdym momencie odpowiadać
wielkości posiadanych kryptoaktywów, tak aby łączna ekspozycja
Wnioskodawcy na zmianę ich ceny była bliska zeru. Wnioskodawca nie
zamierza zajmować pozycji spekulacyjnych na kontraktach.
```

> **Перевод.** Заявитель — физическое лицо, польский налоговый резидент, не ведёт
> предпринимательской деятельности. Намерен вести инвестиционную деятельность на
> рынке криптоактивов с помощью собственной программы, двумя связанными частями:
> (1) на криптобирже покупает и продаёт BTC, ETH, SOL **исключительно в парах с
> USDC**, не обменивая их на законное платёжное средство; USDC — токен,
> привязанный к доллару, эмитент Circle, нотифицирован в ЕС как токен электронных
> денег (EMT) по MiCA; (2) параллельно, **исключительно для ограничения риска
> изменения цены**, открывает противоположные (короткие) позиции по бессрочным
> фьючерсам PF_XBTUSD / PF_ETHUSD / PF_SOLUSD на Kraken для клиентов ЕОГ; контракты
> **без срока истечения**, номинированы в USD, рассчитываются только денежно, без
> поставки базового актива; залог намерен внести **в USDC** и валюту расчёта
> результата **установить в USDC**. Размер позиции по контрактам в каждый момент
> соответствует размеру имеющихся криптоактивов, чтобы суммарная экспозиция была
> близка к нулю. Спекулятивных позиций по контрактам заявитель открывать не
> намерен.

⚠️ Последний абзац — не украшение. Он отделяет хедж от спекуляции, и именно на него
KIS будет смотреть, решая, считать ли две ноги одной операцией.

## H. PYTANIA — вопросы

```
1. Czy dochód (strata) osiągany przez Wnioskodawcę na kontraktach
opisanych w pkt 2 opisu zdarzenia przyszłego stanowi dochód
z odpłatnego zbycia pochodnych instrumentów finansowych, o którym
mowa w art. 30b ust. 1 ustawy o podatku dochodowym od osób
fizycznych, czy dochód z odpłatnego zbycia walut wirtualnych,
o którym mowa w art. 30b ust. 1a tej ustawy?

2. Czy USDC stanowi walutę wirtualną w rozumieniu ustawy o podatku
dochodowym od osób fizycznych, a w konsekwencji czy wymiana BTC, ETH
lub SOL na USDC oraz USDC na BTC, ETH lub SOL stanowi wymianę
pomiędzy walutami wirtualnymi, która zgodnie z art. 17 ust. 1f tej
ustawy nie powoduje powstania przychodu?

3. Czy ustawienie waluty rozliczenia wyniku na kontraktach na USDC,
to jest rozliczanie wyniku w walucie wirtualnej, a nie w prawnym
środku płatniczym, ma wpływ na odpowiedź na pytanie nr 1?

4. Czy wniesienie USDC jako depozytu zabezpieczającego pozycję na
kontraktach, a następnie jego zwrot, powoduje powstanie przychodu
z odpłatnego zbycia waluty wirtualnej?
```

> **Перевод.** 1. Результат по описанным контрактам — это доход от отчуждения
> производных финансовых инструментов (ст. 30b ч. 1) или от отчуждения виртуальных
> валют (ст. 30b ч. 1a)? 2. Является ли USDC виртуальной валютой, и значит ли это,
> что обмен BTC/ETH/SOL ↔ USDC — это обмен между виртуальными валютами, который по
> ст. 17 ч. 1f не образует дохода? 3. Влияет ли установка валюты расчёта результата
> в USDC (то есть расчёт в виртуальной валюте, а не в законном платёжном средстве)
> на ответ на вопрос 1? 4. Образует ли внесение USDC в качестве залога и его
> последующий возврат доход от отчуждения виртуальной валюты?

🔑 **Вопрос 2 — самый практичный из четырёх.** От него зависит, возникает ли
налоговое событие на **каждом** круге бота. При десятках тысяч сделок в год разница
между «событий нет» и «событие на каждой продаже» — это разница между одной строкой
в декларации и неподъёмным учётом.

**Вопрос 1 — самый денежный.** От него зависит поправка V-bis.1 и порог допуска.

## I. WŁASNE STANOWISKO — собственная позиция

🔑 Пишем ту позицию, которая выгодна: если KIS не ответит за три месяца, она
становится обязательной.

```
Ad 1. W ocenie Wnioskodawcy dochód z opisanych kontraktów stanowi
dochód z odpłatnego zbycia walut wirtualnych, o którym mowa w art. 30b
ust. 1a ustawy o PIT. Pojęcie pochodnego instrumentu finansowego
ustawa o PIT definiuje przez odesłanie do ustawy o obrocie
instrumentami finansowymi. Opisane kontrakty nie mają terminu
wygaśnięcia, nie są dopuszczone do obrotu na rynku regulowanym,
a ich wynik jest w całości zdeterminowany ceną waluty wirtualnej
i rozliczany w walucie wirtualnej. Ekonomicznie stanowią one zatem
pozycję w walucie wirtualnej, a nie odrębny instrument pochodny.

Ad 2. W ocenie Wnioskodawcy USDC stanowi walutę wirtualną
w rozumieniu ustawy o PIT, ponieważ odpowiada definicji waluty
wirtualnej zawartej w ustawie o przeciwdziałaniu praniu pieniędzy
i finansowaniu terroryzmu, do której ustawa o PIT odsyła.
W konsekwencji wymiana BTC, ETH lub SOL na USDC oraz USDC na te
kryptoaktywa stanowi wymianę pomiędzy walutami wirtualnymi i zgodnie
z art. 17 ust. 1f ustawy o PIT nie powoduje powstania przychodu.

Ad 3. W ocenie Wnioskodawcy rozliczanie wyniku w USDC dodatkowo
potwierdza stanowisko przedstawione w Ad 1, ponieważ w takim
przypadku żaden etap transakcji nie obejmuje prawnego środka
płatniczego.

Ad 4. W ocenie Wnioskodawcy wniesienie depozytu zabezpieczającego
nie powoduje powstania przychodu, ponieważ nie następuje odpłatne
zbycie waluty wirtualnej — Wnioskodawca nie otrzymuje w zamian
prawnego środka płatniczego, towaru, usługi ani prawa majątkowego,
a jedynie ustanawia zabezpieczenie, które podlega zwrotowi.
```

> **Перевод.** *Ad 1.* По мнению заявителя — доход от отчуждения виртуальных валют
> (ст. 30b ч. 1a). Понятие производного финансового инструмента PIT определяет
> отсылкой к закону об обороте финансовых инструментов; описанные контракты **не
> имеют срока истечения, не допущены к обороту на регулируемом рынке**, их
> результат полностью определяется ценой виртуальной валюты и рассчитывается в
> виртуальной валюте — экономически это позиция в виртуальной валюте, а не
> отдельный дериватив. *Ad 2.* USDC — виртуальная валюта по определению из закона
> о противодействии отмыванию, к которому отсылает PIT; следовательно обмен
> BTC/ETH/SOL ↔ USDC дохода не образует (ст. 17 ч. 1f). *Ad 3.* Расчёт в USDC
> дополнительно подтверждает позицию Ad 1: ни на одном этапе нет законного
> платёжного средства. *Ad 4.* Внесение залога дохода не образует — отчуждения нет,
> взамен ничего не получено, залог подлежит возврату.

⚠️ **Проверьте номера статей перед отправкой.** Подтверждены по источникам:
`art. 17 ust. 1f` (обмен крипты на крипту не доход), `art. 30b ust. 1` (ценные
бумаги и деривативы), `art. 30b ust. 1a` (виртуальные валюты, 19 %),
`art. 22 ust. 14` и `art. 22 ust. 16` (расходы и их бессрочный перенос). Отсылки
на определения (`art. 5a`) я намеренно не ставил цифрами — формулировка через
«przez odesłanie do ustawy o obrocie instrumentami finansowymi» работает и без
номера.

---

# Часть IV. Куда и как подавать

| | |
|---|---|
| **кто выдаёт** | Krajowa Informacja Skarbowa (KIS) |
| **адрес для бумаги** | ul. Warszawska 5, 43-300 Bielsko-Biała |
| **онлайн** | **e-Urząd Skarbowy** на `podatki.gov.pl` — вход через login.gov.pl (profil zaufany, mObywatel, банк или e-dowód), далее «wniosek o wydanie interpretacji indywidualnej (ORD-IN)» |
| **альтернатива** | e-Doręczenia на адрес KIS, либо бумажный ORD-IN почтой |
| **стоимость** | **40 zł за каждое отдельное состояние дел или будущее событие** |
| **срок оплаты** | 7 дней с подачи, на счёт KIS (реквизиты показываются в форме) |
| **срок ответа** | до **3 месяцев**; не уложились — ваша позиция считается правильной |

## Сколько платить за четыре вопроса

Формально событие одно, значит 40 zł. **На практике KIS часто считает каждый
вопрос отдельным предметом** и присылает `wezwanie` на доплату, что тянет ещё
7 дней.

🔑 Проще заплатить сразу **160 zł (4 × 40)**: переплату возвращают по заявлению, а
недоплата стоит времени.

## Порядок действий

1. собрать описание (часть III, блок G) — при необходимости поправить под то, что
   реально будет: биржа, пары, площадка деривативов;
2. вписать вопросы (блок H) и собственную позицию (блок I);
3. подать через e-Urząd Skarbowy, оплатить 160 zł в течение 7 дней;
4. отметить в календаре дату **+3 месяца**: если ответа нет — ваша позиция в силе,
   и это стоит зафиксировать письменно;
5. ответ подшить к документам проекта: он защищает только то, что в нём описано,
   поэтому при изменении конструкции (другая площадка, другой контракт, спотовый
   шорт вместо перпа) запрос надо подавать заново.

⚠️ И главное про сроки: **подавать до того, как хедж заработает живьём.** Сейчас
это `zdarzenie przyszłe` и защита полная. После запуска станет `stan faktyczny`, и
защита сократится до «без штрафа, но налог доначислят».

---

*Составлено 20.09.2026. Источники: брошюра Минфина к PIT-38, gov.pl (порядок
получения интерпретации), Kryptoprawo (разъяснение Минфина по SWAP и CFD), Divly,
справка Kraken по залоговым валютам. Я не налоговый консультант — это заготовка для
самостоятельной подачи, а не заключение.*
