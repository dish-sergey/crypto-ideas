# METIS — Metis

| | |
|---|---|
| Инструмент Kraken | `PF_METISUSD` (перп, USD) |
| CoinGecko id | metis-token |
| Капитализация | $21.08 млн |
| Цена | $2.66 |
| Циркулирующее предложение | 7.93 млн |
| Всего / максимум | 10 млн / 10 млн |

## Что за монета

Metis — optimistic L2 с децентрализованными секвенсорами.

## Разлоки

**Статус: НАЙДЕНЫ.** Источник — DefiLlama emissions (бесплатно, без ключа).

- Протокол DefiLlama: `metis` («Metis»)
- Категория / сеть: Canonical Bridge / Metis
- Событий в расписании: **71** (прошедших 71, будущих cliff-событий 0)
- Максимальное предложение по расписанию: 10 млн
- Не распределено (TBD): 2 млн

### Аллокация (доля финального предложения, %)

| Транш | сейчас | финал | разлочено |
|---|---|---|---|
| IDO | 0.4 | 0.4 | 100% |
| Airdrop | 8.2 | 7.5 | 100% |
| Liquidity Reserve | 8.2 | 7.5 | 100% |
| Seed Investors | 8.2 | 7.5 | 100% |
| Private Investors | 9.5 | 8.7 | 100% |
| Community Star Investors | 4.1 | 3.7 | 100% |
| Strategic Investors | 2 | 1.9 | 100% |
| Founding Team | 9.5 | 8.7 | 100% |
| Community Development | 12.3 | 11.3 | 100% |
| MetisLab Foundation | 5.5 | 5 | 100% |
| Advisors | 2 | 1.9 | 100% |
| Angel Investors | 1.4 | 1.2 | 100% |
| Transaction Mining | 28.7 | 34.6 | 75.9% |

### Ближайшие разлоки (cliff)

Дискретных (cliff) событий впереди нет — для S5, который торгует именно обрывы, монета сейчас событий не даёт.
Это НЕ значит, что предложение не растёт: см. непрерывную эмиссию ниже.

### Непрерывная эмиссия (весь ряд `unlocked`)

Суммарный прирост разлоченного предложения по посуточному ряду — включает `linear`-транши, стейкинг-награды и майнинг, а не только обрывы:

| Горизонт | Прирост | % циркуляции |
|---|---|---|
| +30 дней | 11.7 тыс | 0.15% |
| +90 дней | 35.2 тыс | 0.44% |
| до конца расписания | 669.5 тыс | 8.44% |

⚠️ Для монет с майнинговой или стейкинговой эмиссией эта таблица показывает инфляцию, а не разлоки. Событие S5 — только строка из таблицы cliff выше.

## Автосбор

**Да, полностью автоматизируемо.**

```
GET https://defillama-datasets.llama.fi/emissions/metis
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
