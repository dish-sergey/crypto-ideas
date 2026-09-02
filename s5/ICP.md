# ICP — Internet Computer

| | |
|---|---|
| Инструмент Kraken | `PF_ICPUSD` (перп, USD) |
| CoinGecko id | internet-computer |
| Капитализация | $1.4 млрд |
| Цена | $2.52 |
| Циркулирующее предложение | 556.2 млн |
| Всего / максимум | 556.2 млн / не ограничен |

## Что за монета

Internet Computer — L1 от DFINITY для размещения приложений целиком ончейн.

## Разлоки

**Статус: НАЙДЕНЫ.** Источник — DefiLlama emissions (бесплатно, без ключа).

- Протокол DefiLlama: `internet-computer` («Internet Computer»)
- Категория / сеть: Chain / 
- Событий в расписании: **64** (прошедших 64, будущих cliff-событий 0)
- Максимальное предложение по расписанию: 556.19 млн

### Аллокация (доля финального предложения, %)

| Транш | сейчас | финал | разлочено |
|---|---|---|---|
| Early Contributors | 8 | 8 | 100% |
| Foundation, Team & Partnerships | 44.3 | 44.3 | 100% |
| Presale | 4.2 | 4.2 | 100% |
| Community Airdrop | 1.1 | 1.1 | 100% |
| Strategic Round | 5.9 | 5.9 | 100% |
| Seed Round | 20.9 | 20.9 | 100% |
| Network Rewards | 15.6 | 15.6 | 100% |

### Ближайшие разлоки (cliff)

Дискретных (cliff) событий впереди нет — для S5, который торгует именно обрывы, монета сейчас событий не даёт.
Это НЕ значит, что предложение не растёт: см. непрерывную эмиссию ниже.

### Непрерывная эмиссия (весь ряд `unlocked`)

Суммарный прирост разлоченного предложения по посуточному ряду — включает `linear`-транши, стейкинг-награды и майнинг, а не только обрывы:

| Горизонт | Прирост | % циркуляции |
|---|---|---|
| +30 дней | 0 | 0% |
| +90 дней | 0 | 0% |
| до конца расписания | 0 | 0% |

⚠️ Для монет с майнинговой или стейкинговой эмиссией эта таблица показывает инфляцию, а не разлоки. Событие S5 — только строка из таблицы cliff выше.

## Автосбор

**Да, полностью автоматизируемо.**

```
GET https://defillama-datasets.llama.fi/emissions/internet-computer
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
