# GMT — GMT

| | |
|---|---|
| Инструмент Kraken | `PF_GMTUSD` (перп, USD) |
| CoinGecko id | stepn |
| Капитализация | $22.62 млн |
| Цена | $0.00727777 |
| Циркулирующее предложение | 3.11 млрд |
| Всего / максимум | 5.07 млрд / 6 млрд |

## Что за монета

GMT (STEPN) — move-to-earn приложение. TGE март 2022.

## Разлоки

**Статус: НАЙДЕНЫ.** Источник — DefiLlama emissions (бесплатно, без ключа).

- Протокол DefiLlama: `stepn` («Stepn»)
- Категория / сеть: Chain / 
- Событий в расписании: **294** (прошедших 204, будущих cliff-событий 90)
- Максимальное предложение по расписанию: 5.95 млрд
- Не распределено (TBD): 684 млн

### Аллокация (доля финального предложения, %)

| Транш | сейчас | финал | разлочено |
|---|---|---|---|
| Binance Launchpad | 9.1 | 7.1 | 100% |
| Advisors | 3.3 | 2.5 | 100% |
| Private Sale | 21.2 | 16.4 | 100% |
| Team | 15.7 | 14.3 | 85.4% |
| Ecosystem / Treasury | 24.2 | 30.2 | 62% |
| Move And Earn | 26.5 | 29.5 | 69.8% |

### Ближайшие разлоки (cliff)

| Дата | Токенов | % циркуляции | ≈ USD | Транш |
|---|---|---|---|---|
| 2026-09-07 | 50.66 млн | 1.63% | $368.7 тыс | Uncategorized, insiders, noncirculating |
| 2026-10-08 | 50.66 млн | 1.63% | $368.7 тыс | Uncategorized, insiders, noncirculating |
| 2026-11-07 | 50.66 млн | 1.63% | $368.7 тыс | Uncategorized, insiders, noncirculating |
| 2026-12-07 | 50.66 млн | 1.63% | $368.7 тыс | Uncategorized, insiders, noncirculating |
| 2027-01-07 | 50.66 млн | 1.63% | $368.7 тыс | Uncategorized, insiders, noncirculating |
| 2027-02-06 | 50.66 млн | 1.63% | $368.7 тыс | Uncategorized, insiders, noncirculating |
| 2027-03-09 | 32.94 млн | 1.06% | $239.7 тыс | Uncategorized, noncirculating |
| 2027-04-08 | 32.94 млн | 1.06% | $239.7 тыс | Uncategorized, noncirculating |
| 2027-05-09 | 32.94 млн | 1.06% | $239.7 тыс | Uncategorized, noncirculating |
| 2027-06-08 | 32.94 млн | 1.06% | $239.7 тыс | Uncategorized, noncirculating |

**Давление на горизонте S5:** 30 дней — 50.66 млн токенов (1.63% циркуляции, $368.7 тыс; 90 дней — 151.98 млн, 4.88%).

### Непрерывная эмиссия (весь ряд `unlocked`)

Суммарный прирост разлоченного предложения по посуточному ряду — включает `linear`-транши, стейкинг-награды и майнинг, а не только обрывы:

| Горизонт | Прирост | % циркуляции |
|---|---|---|
| +30 дней | 50.66 млн | 1.63% |
| +90 дней | 151.98 млн | 4.88% |
| до конца расписания | 1.34 млрд | 43.02% |

⚠️ Для монет с майнинговой или стейкинговой эмиссией эта таблица показывает инфляцию, а не разлоки. Событие S5 — только строка из таблицы cliff выше.

## Автосбор

**Да, полностью автоматизируемо.**

```
GET https://defillama-datasets.llama.fi/emissions/stepn
```

Без ключа и без лимитов (CDN), JSON. Ключевые поля:

- `metadata.events[]` — дискретные события: `timestamp`, `noOfTokens`, `category`, `unlockType` (`cliff` / `linear`), `rateDurationDays`. Форвардные события присутствуют.
- `documentedData.data[]` — посуточный ряд `{timestamp, unlocked, rawEmission, burned}` по каждому траншу, включая будущее.
- `documentedData.tokenAllocation` — доли траншей (`current` / `final` / `progress`).
- `supplyMetrics` — `maxSupply`, `adjustedSupply`, `tbdAmount`.

⚠️ Платный `api.llama.fi/emissions` (HTTP 402) заменяется этим CDN — данные те же.
⚠️ У `unlockType: "linear"` поле `noOfTokens` хранит **смену скорости** `[было, стало]` за `rateDurationDays`, а не объём. Объём считать по `cliff` либо по разностям ряда `unlocked`.
⚠️ Часть `timestamp` приходит строкой, а не числом — приводить типы при разборе.

---

Сгенерировано 2026-09-02 из Kraken Futures `instruments`, DefiLlama emissions и CoinGecko.
