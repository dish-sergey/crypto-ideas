# ARKM — Arkham

| | |
|---|---|
| Инструмент Kraken | `PF_ARKMUSD` (перп, USD) |
| CoinGecko id | arkham |
| Капитализация | $74.8 млн |
| Цена | $0.10761 |
| Циркулирующее предложение | 695.06 млн |
| Всего / максимум | 1 млрд / 1 млрд |
| Теги | Ethereum (ETH) Token (ERC-20), AI (Artificial Intelligence), Marketplace, Governance, Binance Launchpad, Made in USA |

## Что за монета

Arkham — платформа ончейн-разведки и деанонимизации. TGE 2023.

## Разлоки

**Статус: НАЙДЕНЫ.** Источник — DefiLlama emissions (бесплатно, без ключа).

- Протокол DefiLlama: `arkham` («Arkham»)
- Категория / сеть: Services / 
- Событий в расписании: **8** (прошедших 8, будущих cliff-событий 0)
- Максимальное предложение по расписанию: 1 млрд
- Не распределено (TBD): 78.52 млн

### Аллокация (доля финального предложения, %)

| Транш | сейчас | финал | разлочено |
|---|---|---|---|
| Binance Launchpad | 7.6 | 5 | 100% |
| Investors | 19 | 17.5 | 71% |
| Advisors | 3.3 | 3 | 71% |
| Core Contributors | 16.3 | 20 | 53.3% |
| Ecosystem Incentives and Grants | 39.6 | 37.3 | 69.6% |
| Foundation Treasury | 14.3 | 17.2 | 54.3% |

### Ближайшие разлоки (cliff)

Дискретных (cliff) событий впереди нет — для S5, который торгует именно обрывы, монета сейчас событий не даёт.
Это НЕ значит, что предложение не растёт: см. непрерывную эмиссию ниже.

### Непрерывная эмиссия (весь ряд `unlocked`)

Суммарный прирост разлоченного предложения по посуточному ряду — включает `linear`-транши, стейкинг-награды и майнинг, а не только обрывы:

| Горизонт | Прирост | % циркуляции |
|---|---|---|
| +30 дней | 16.36 млн | 2.35% |
| +90 дней | 49.09 млн | 7.06% |
| до конца расписания | 344.84 млн | 49.61% |

⚠️ Для монет с майнинговой или стейкинговой эмиссией эта таблица показывает инфляцию, а не разлоки. Событие S5 — только строка из таблицы cliff выше.

## Автосбор

**Да, полностью автоматизируемо.**

```
GET https://defillama-datasets.llama.fi/emissions/arkham
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
