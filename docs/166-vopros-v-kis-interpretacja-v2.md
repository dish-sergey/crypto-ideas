# 166. Запрос в KIS: текст на польском, куда и как подавать (v2)

Вспомогательный документ к `165-...-v2.md`, часть V-bis.
⚠️ Я не налоговый консультант. Ниже — заготовка из открытых источников; номера
статей и суммы стоит перепроверить перед отправкой.

**Версия v2:** сокращено с четырёх вопросов до **двух** (80 zł вместо 160).
Вопрос про валюту расчёта вписан фактом в описание и в текст вопроса 1, вопрос про
залог убран. Добавлена часть II — почему вопрос про USDC оказался не
второстепенным, а решающим.

---

# Часть I. Залог в USDC — что он меняет

**USDC принимается как залог** в мультизалоговом кошельке Kraken Derivatives.

| залог | дисконт (haircut) | комиссия конверсии |
|---|---:|---:|
| USD / EUR | 0 % | 0 % |
| **USDC** | **0.50 %** | **0 %** |
| USDT | 0.50 % | 0 % |
| BTC | 1 % | 0.20 % |

Плюс деталь, которая пригодится ниже: результат в мультизалоговом кошельке по
умолчанию книжится в USD, но **валюту расчёта можно вручную переключить** на любую
залоговую — в том числе на USDC.

**Операционно это в плюс:** исчезают фиатные рельсы (Revolut ↔ Kraken ходит как
USDC, быстро и в выходные), исчезает EUR/USD-экспозиция на залоге, и валюта залога
совпадает со спотовой ногой — при отклонении USDC от доллара обе части книги едут
вместе. Цена — дисконт 0.5 % на марже в 1 % от позиции, то есть округление.

**Налогово сам по себе залог почти наверняка нейтрален** — внесение депозита не
является отчуждением, взамен ничего не получено, депозит возвращается. Поэтому
отдельный вопрос про него из запроса убран.

🔑 **Но обе детали — залог в USDC и расчёт в USDC — остаются в описании и в тексте
вопроса 1.** Они там не для красоты: если ни на одном этапе сделки не появляется
законное платёжное средство, это лучший аргумент за то, что результат по контракту
относится к виртуальным валютам, а не к деривативам.

---

# Часть II. Почему вопрос про USDC — не формальность

Общее правило вы знаете и оно верное: налог только при обмене на фиат, товар или
услугу; обмен крипты на крипту дохода не образует (`art. 17 ust. 1f`). И KIS
подтверждает это прямо по стейблкоинам: MiCA не меняет польское налоговое право, а
USDT и USDC не являются электронными деньгами в смысле польского закона о платёжных
услугах.

⚠️ **Проблема в том, что это держится на неформальной позиции, а не на норме.**

Закон `o rynku kryptoaktywów`, который должен был закрыть дыру, **ветирован
президентом дважды** — в декабре и повторно. И в нём была ровно эта норма: токены,
признанные по MiCA электронными деньгами (в тексте прямо назван USDC), **всё равно
считаются виртуальными валютами для налоговых целей**.

Закона нет — двусмысленность осталась. Prawo.pl формулирует это прямо: сейчас она
создаёт **риск неожиданного 19 % налога на конвертацию стейблкоинов**. MiCA при
этом применяется напрямую, а имплементирующего закона в Польше нет.

Итого: между вами и налоговым событием на каждом круге стоит позиция KIS, данная
кому-то другому и **никого лично не связывающая**.

## 🔑 И вот где это бьёт по проекту

Не в учёте тысяч сделок — учёт был бы неприятен, но переживаем.

**Дело в блоке 4 — засеве.** Это когда вы отдаёте боту свои долгосрочные биткоины,
и он начинает их продавать.

| позиция по USDC | что происходит при засеве |
|---|---|
| USDC — виртуальная валюта (сейчас) | обмен крипты на крипту, налогового события нет |
| USDC — не виртуальная валюта | **каждая продажа — реализация вашей многолетней прибыли по низкой базе** |

Бот зарабатывает порядка $128 в год. 19 % от накопленного роста вашего стека — это
величина совсем другого порядка. **То есть вопрос 2 решает не бухгалтерию, а можно
ли вообще трогать долгосрочные монеты ботом.**

## Что интерпретация даст, а что нет

| | |
|---|---|
| **защитит** | от пересмотра позиции налоговой **при том же законе** — а это и есть живой риск, раз официальных разъяснений Минфина по MiCA нет |
| **не защитит** | от нового закона: интерпретация выдаётся под действующие нормы и с их изменением теряет силу |

Ценность не в том, чтобы узнать ответ — ответ известен. Ценность в том, чтобы
сделать его **обязательным лично для вас и зафиксировать до того, как бот начнёт
продавать ваши монеты.**

---

# Часть III. 🔑 Тактика: подавать как «zdarzenie przyszłe», и подавать сейчас

Это важнее самого текста.

| | |
|---|---|
| интерпретация получена **ДО** события | защита полная: ни доначисления, ни штрафа, ни уголовной ответственности |
| интерпретация получена **ПОСЛЕ** события | защита только от штрафа и уголовки — **налог доначислят** |

**Хедж вы живьём ещё не запускали**, засев тем более — обе конструкции пока только
посчитаны. Значит они честно описываются как **`zdarzenie przyszłe`**, и это даёт
полную защиту. После запуска такой возможности не будет.

И второе: ответ идёт **до трёх месяцев**, а если KIS не уложится — **ваша позиция
считается правильной автоматически**. Поэтому в разделе `własne stanowisko` пишем
тот ответ, который выгоден, а не тот, который кажется вероятным.

Про шансы, честно:

- **вопрос 1** — позиция слабее. Kraken в ЕОГ работает как MTF, крипто-деривативы
  по MiFID II считаются финансовыми инструментами. Скорее всего ответят
  «pochodny instrument finansowy». Отрицательный ответ ничего не стоит, а
  положительный снимает поправку V-bis.1 целиком;
- **вопрос 2** — позиция **сильная**: она дословно совпадает с тем, что KIS уже
  говорил публично. Тут ожидаем «prawidłowe», и смысл именно в том, чтобы получить
  это лично и письменно.

---

# Часть IV. Текст запроса

Три блока формы ORD-IN. Польский текст, под каждым перевод.

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
będący e-pieniądzem (EMT) w rozumieniu rozporządzenia MiCA. Część
kryptoaktywów wykorzystywanych w tej działalności pochodzić będzie
z wcześniejszych, wieloletnich zakupów Wnioskodawcy.

2. Równolegle, wyłącznie w celu ograniczenia ryzyka zmiany ceny
posiadanych kryptoaktywów, Wnioskodawca będzie zajmował przeciwstawne
(krótkie) pozycje na bezterminowych kontraktach terminowych typu
perpetual futures (PF_XBTUSD, PF_ETHUSD, PF_SOLUSD) na platformie
Kraken, prowadzonej dla klientów z Europejskiego Obszaru
Gospodarczego. Kontrakty te nie mają terminu wygaśnięcia, nie są
dopuszczone do obrotu na rynku regulowanym i są rozliczane wyłącznie
pieniężnie, bez dostawy instrumentu bazowego. Wnioskodawca zamierza
wnieść zabezpieczenie (depozyt zabezpieczający) tych pozycji w USDC
oraz ustawić walutę rozliczenia wyniku na USDC, tak aby na żadnym
etapie transakcji nie występował prawny środek płatniczy.

Wielkość pozycji na kontraktach będzie w każdym momencie odpowiadać
wielkości posiadanych kryptoaktywów, tak aby łączna ekspozycja
Wnioskodawcy na zmianę ich ceny była bliska zeru. Wnioskodawca nie
zamierza zajmować pozycji spekulacyjnych na kontraktach.
```

> **Перевод.** Заявитель — физлицо, польский налоговый резидент, без
> предпринимательской деятельности. Намерен вести инвестиционную деятельность на
> рынке криптоактивов с помощью собственной программы, двумя связанными частями:
> (1) покупает и продаёт BTC, ETH, SOL **исключительно в парах с USDC**, не
> обменивая на законное платёжное средство; USDC — токен, привязанный к доллару,
> эмитент Circle, нотифицирован в ЕС как токен электронных денег (EMT) по MiCA;
> **часть используемых криптоактивов происходит из прежних многолетних покупок**;
> (2) параллельно, **исключительно для ограничения ценового риска**, открывает
> противоположные (короткие) позиции по бессрочным фьючерсам PF_XBTUSD / PF_ETHUSD
> / PF_SOLUSD на Kraken для клиентов ЕОГ; контракты **без срока истечения, не
> допущены к обороту на регулируемом рынке**, рассчитываются только денежно, без
> поставки базового актива; залог намерен внести **в USDC**, валюту расчёта
> установить **в USDC**, чтобы **ни на одном этапе не появлялось законное платёжное
> средство**. Размер позиции по контрактам в каждый момент соответствует размеру
> имеющихся криптоактивов, чтобы суммарная экспозиция была близка к нулю.
> Спекулятивных позиций открывать не намерен.

⚠️ Две фразы тут несут нагрузку и их лучше не вычёркивать:

- **про многолетние покупки** — она нужна вопросу 2: именно эти монеты попадут под
  реализацию, если позиция по USDC перевернётся;
- **последний абзац** — он отделяет хедж от спекуляции, и на него KIS будет
  смотреть, решая, считать ли две ноги одной операцией.

## H. PYTANIA — вопросы

```
1. Czy dochód (strata) osiągany przez Wnioskodawcę na bezterminowych
kontraktach terminowych opisanych w pkt 2 opisu zdarzenia przyszłego,
przy uwzględnieniu że zabezpieczenie tych pozycji zostanie wniesione
w USDC, a waluta rozliczenia wyniku zostanie ustawiona na USDC,
stanowi dochód z odpłatnego zbycia pochodnych instrumentów
finansowych, o którym mowa w art. 30b ust. 1 ustawy o podatku
dochodowym od osób fizycznych, czy dochód z odpłatnego zbycia walut
wirtualnych, o którym mowa w art. 30b ust. 1a tej ustawy?

2. Czy USDC stanowi walutę wirtualną w rozumieniu ustawy o podatku
dochodowym od osób fizycznych, także po rozpoczęciu bezpośredniego
stosowania rozporządzenia MiCA, na gruncie którego USDC jest tokenem
będącym e-pieniądzem, a w konsekwencji czy wymiana BTC, ETH lub SOL
na USDC oraz USDC na BTC, ETH lub SOL stanowi wymianę pomiędzy
walutami wirtualnymi, która zgodnie z art. 17 ust. 1f tej ustawy nie
powoduje powstania przychodu?
```

> **Перевод.** 1. Результат по описанным бессрочным контрактам — с учётом того, что
> залог внесён в USDC и валюта расчёта установлена в USDC — это доход от отчуждения
> производных финансовых инструментов (ст. 30b ч. 1) или от отчуждения виртуальных
> валют (ст. 30b ч. 1a)? 2. Является ли USDC виртуальной валютой по закону о PIT —
> **в том числе после начала прямого применения MiCA, по которому USDC является
> токеном электронных денег**, — и значит ли это, что обмен BTC/ETH/SOL ↔ USDC есть
> обмен между виртуальными валютами, который по ст. 17 ч. 1f не образует дохода?

🔑 Во втором вопросе оговорка про MiCA обязательна. Без неё KIS ответит на общий
вопрос, ответ на который вы и так знаете, и защита не покроет как раз тот случай,
ради которого всё затевается.

## I. WŁASNE STANOWISKO — собственная позиция

Пишем выгодную: если KIS не ответит за три месяца, она становится обязательной.

```
Ad 1. W ocenie Wnioskodawcy dochód z opisanych kontraktów stanowi
dochód z odpłatnego zbycia walut wirtualnych, o którym mowa w art. 30b
ust. 1a ustawy o PIT. Pojęcie pochodnego instrumentu finansowego
ustawa o PIT definiuje przez odesłanie do ustawy o obrocie
instrumentami finansowymi. Opisane kontrakty nie mają terminu
wygaśnięcia i nie są dopuszczone do obrotu na rynku regulowanym,
ich wynik jest w całości zdeterminowany ceną waluty wirtualnej,
zabezpieczenie pozycji wnoszone jest w walucie wirtualnej, a wynik
rozliczany jest w walucie wirtualnej. Na żadnym etapie transakcji
nie występuje prawny środek płatniczy. Ekonomicznie kontrakty te
stanowią zatem pozycję w walucie wirtualnej, a nie odrębny instrument
pochodny.

Ad 2. W ocenie Wnioskodawcy USDC stanowi walutę wirtualną w rozumieniu
ustawy o PIT. Ustawa o PIT odsyła w tym zakresie do definicji zawartej
w ustawie o przeciwdziałaniu praniu pieniędzy i finansowaniu
terroryzmu, a rozporządzenie MiCA nie zmienia przepisów podatkowych
ani definicji, do których przepisy podatkowe odsyłają. USDC nie jest
również pieniądzem elektronicznym w rozumieniu ustawy o usługach
płatniczych. W konsekwencji wymiana BTC, ETH lub SOL na USDC oraz
USDC na BTC, ETH lub SOL stanowi wymianę pomiędzy walutami wirtualnymi
i zgodnie z art. 17 ust. 1f ustawy o PIT nie powoduje powstania
przychodu, niezależnie od liczby dokonanych transakcji oraz
niezależnie od tego, kiedy sprzedawane kryptoaktywa zostały nabyte.
```

> **Перевод.** *Ad 1.* По мнению заявителя — доход от отчуждения виртуальных валют
> (ст. 30b ч. 1a). Производный финансовый инструмент PIT определяет отсылкой к
> закону об обороте финансовых инструментов; описанные контракты **без срока
> истечения, не допущены к регулируемому рынку**, результат полностью определяется
> ценой виртуальной валюты, залог вносится в виртуальной валюте, результат
> рассчитывается в виртуальной валюте — законного платёжного средства нет ни на
> одном этапе. Экономически это позиция в виртуальной валюте, а не отдельный
> дериватив. *Ad 2.* USDC — виртуальная валюта по PIT: PIT отсылает к определению
> из закона о противодействии отмыванию, а MiCA не меняет ни налоговых норм, ни
> определений, к которым они отсылают; USDC также не является электронными деньгами
> в смысле закона о платёжных услугах. Следовательно обмен BTC/ETH/SOL ↔ USDC
> дохода не образует (ст. 17 ч. 1f), **независимо от числа сделок и независимо от
> того, когда продаваемые криптоактивы были приобретены**.

🔑 Последняя оговорка в Ad 2 — самая ценная строка во всём запросе. Она закрывает
ровно тот случай, который нужен блоку 4: продажу монет, купленных годы назад.

⚠️ **Номера статей перепроверьте.** Подтверждены по источникам: `art. 17 ust. 1f`
(обмен крипты на крипту не доход), `art. 30b ust. 1` (ценные бумаги и деривативы),
`art. 30b ust. 1a` (виртуальные валюты, 19 %), `art. 22 ust. 14` и `art. 22 ust. 16`
(расходы и их бессрочный перенос). Ссылки на определения (`art. 5a`) намеренно даны
словами, а не номерами — формулировка через «przez odesłanie do ustawy o obrocie
instrumentami finansowymi» работает и без номера.

---

# Часть V. Куда и как подавать

| | |
|---|---|
| **кто выдаёт** | Krajowa Informacja Skarbowa (KIS) |
| **онлайн** | **e-Urząd Skarbowy** на `podatki.gov.pl` — вход через login.gov.pl (profil zaufany, mObywatel, банк, e-dowód), далее «wniosek o wydanie interpretacji indywidualnej (ORD-IN)» |
| **альтернатива** | e-Doręczenia на адрес KIS, либо бумажный ORD-IN почтой |
| **адрес для бумаги** | ul. Warszawska 5, 43-300 Bielsko-Biała |
| **стоимость** | 40 zł за каждое отдельное состояние дел или будущее событие → **при двух вопросах 80 zł** |
| **срок оплаты** | 7 дней с подачи, на счёт KIS (реквизиты показываются в форме) |
| **срок ответа** | до **3 месяцев**; не уложились — ваша позиция считается правильной |

Формально событие одно, то есть теоретически хватит 40 zł. Но KIS часто считает
каждый вопрос отдельным предметом и присылает `wezwanie` на доплату — это ещё
неделя. **Проще заплатить 80 zł сразу:** переплату возвращают по заявлению.

## Порядок действий

1. поправить описание (часть IV, блок G) под то, что реально будет: название
   биржи, пары, площадка деривативов;
2. вписать два вопроса (блок H) и позицию по каждому (блок I);
3. подать через e-Urząd Skarbowy, оплатить 80 zł в течение 7 дней;
4. отметить в календаре **+3 месяца**: если ответа нет — ваша позиция в силе, и это
   стоит зафиксировать письменно;
5. ответ подшить к документам проекта. Он защищает только то, что в нём описано,
   поэтому при смене конструкции (другая площадка, другой контракт, спотовый шорт
   вместо перпа) запрос подаётся заново.

⚠️ И главное про сроки: **подавать до того, как хедж и засев заработают живьём.**
Сейчас это `zdarzenie przyszłe` и защита полная. После запуска станет
`stan faktyczny`, и защита сократится до «без штрафа, но налог доначислят».

---

*Составлено 20.09.2026, версия v2. Источники: брошюра Минфина к PIT-38, gov.pl
(порядок получения интерпретации), Kryptoprawo (разъяснение Минфина по SWAP и CFD),
Kryptokancelaria и Comparic (позиция KIS по стейблкоинам), Prawo.pl (вето на закон
о рынке криптоактивов), справка Kraken по залоговым валютам. Я не налоговый
консультант — это заготовка для самостоятельной подачи, а не заключение.*
