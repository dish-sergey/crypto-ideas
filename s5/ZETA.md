# ZETA — ZetaChain

| | |
|---|---|
| Инструмент Kraken | `PF_ZETAUSD` (перп, USD) |
| CoinGecko id | zetachain |
| Капитализация | $51.87 млн |
| Цена | $0.03227882 |
| Циркулирующее предложение | 1.61 млрд |
| Всего / максимум | 2.1 млрд / 2.1 млрд |

## Что за монета

ZetaChain — омничейн L1 с нативным доступом к биткоину. TGE январь 2024.

## Разлоки

**Статус: НАЙДЕНЫ.** Источник — DefiLlama emissions (бесплатно, без ключа).

- Протокол DefiLlama: `zetachain` («ZetaChain»)
- Категория / сеть: Chain / ZetaChain
- Событий в расписании: **230** (прошедших 169, будущих cliff-событий 61)
- Максимальное предложение по расписанию: 2.1 млрд
- Не распределено (TBD): 289.33 млн

### Аллокация (доля финального предложения, %)

| Транш | сейчас | финал | разлочено |
|---|---|---|---|
| Core Contributors | 26.2 | 22.5 | 88.9% |
| Purchasers & Advisors | 18.6 | 16 | 88.9% |
| User Growth Pool | 11.5 | 10 | 87.5% |
| Ecosystem Growth Fund | 10.5 | 12 | 66.7% |
| Protocol Treasury | 18.6 | 24 | 59.3% |
| Validator Incentives | 8.5 | 10 | 64.7% |
| Liquidity Incentives | 6 | 5.5 | 83.9% |

### Ближайшие разлоки (cliff)

| Дата | Токенов | % циркуляции | ≈ USD | Транш |
|---|---|---|---|---|
| 2026-10-01 | 44.26 млн | 2.75% | $1.43 млн | ecosystem, farming, insiders, noncirculating |
| 2026-11-01 | 44.26 млн | 2.75% | $1.43 млн | ecosystem, farming, insiders, noncirculating |
| 2026-12-01 | 44.26 млн | 2.75% | $1.43 млн | ecosystem, farming, insiders, noncirculating |
| 2027-01-01 | 21.8 млн | 1.36% | $703.7 тыс | ecosystem, farming, noncirculating |
| 2027-01-31 | 21.8 млн | 1.36% | $703.7 тыс | ecosystem, farming, noncirculating |
| 2027-03-03 | 21.8 млн | 1.36% | $703.7 тыс | ecosystem, farming, noncirculating |
| 2027-04-02 | 21.8 млн | 1.36% | $703.7 тыс | ecosystem, farming, noncirculating |
| 2027-05-03 | 21.8 млн | 1.36% | $703.7 тыс | ecosystem, farming, noncirculating |
| 2027-06-02 | 21.8 млн | 1.36% | $703.7 тыс | ecosystem, farming, noncirculating |
| 2027-07-02 | 19.18 млн | 1.19% | $619 тыс | farming, noncirculating |

**Давление на горизонте S5:** 30 дней — 44.26 млн токенов (2.75% циркуляции, $1.43 млн; 90 дней — 88.52 млн, 5.51%).

### Непрерывная эмиссия (весь ряд `unlocked`)

Суммарный прирост разлоченного предложения по посуточному ряду — включает `linear`-транши, стейкинг-награды и майнинг, а не только обрывы:

| Горизонт | Прирост | % циркуляции |
|---|---|---|
| +30 дней | 48.57 млн | 3.02% |
| +90 дней | 101.46 млн | 6.31% |
| до конца расписания | 498.18 млн | 31% |

⚠️ Для монет с майнинговой или стейкинговой эмиссией эта таблица показывает инфляцию, а не разлоки. Событие S5 — только строка из таблицы cliff выше.

## Автосбор

**Да, полностью автоматизируемо.**

```
GET https://defillama-datasets.llama.fi/emissions/zetachain
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
