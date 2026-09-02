# CRV — Curve DAO

| | |
|---|---|
| Инструмент Kraken | `PF_CRVUSD` (перп, USD) |
| CoinGecko id | curve-dao-token |
| Капитализация | $571.07 млн |
| Цена | $0.367693 |
| Циркулирующее предложение | 1.55 млрд |
| Всего / максимум | 2.41 млрд / 3.03 млрд |
| Теги | Ethereum (ETH) Token (ERC-20), DeFi, Polygon (MATIC) Token, Fantom (FTM) Token, Arbitrum Ecosystem, Optimism Ecosystem |

## Что за монета

Curve — DEX для стейблкоинов, модель veCRV с непрерывной эмиссией.

## Разлоки

**Статус: НАЙДЕНЫ.** Источник — DefiLlama emissions (бесплатно, без ключа).

- Протокол DefiLlama: `curve-finance` («Curve Finance»)
- Категория / сеть: Dexs / 
- Событий в расписании: **10** (прошедших 10, будущих cliff-событий 0)
- Максимальное предложение по расписанию: 3.03 млрд

### Аллокация (доля финального предложения, %)

| Транш | сейчас | финал | разлочено |
|---|---|---|---|
| Community Reserve | 6.3 | 6.3 | 100% |
| Early Users | 6.3 | 6.3 | 100% |
| Employees | 3.8 | 3.8 | 100% |
| Team & Investors | 37.6 | 37.6 | 100% |
| Community | 46.2 | 46.2 | 100% |

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
GET https://defillama-datasets.llama.fi/emissions/curve-finance
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
