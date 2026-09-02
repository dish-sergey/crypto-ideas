# TRUMP — Official Trump

| | |
|---|---|
| Инструмент Kraken | `PF_TRUMPUSD` (перп, USD) |
| CoinGecko id | official-trump |
| Капитализация | $582.21 млн |
| Цена | $2.22 |
| Циркулирующее предложение | 261.88 млн |
| Всего / максимум | 1 млрд / 1 млрд |

## Что за монета

Official Trump — мемкоин, выпущенный в январе 2025 к инаугурации в США.

## Разлоки

**Статус: НАЙДЕНЫ.** Источник — DefiLlama emissions (бесплатно, без ключа).

- Протокол DefiLlama: `official-trump` («Official Trump»)
- Категория / сеть: Meme / 
- Событий в расписании: **14** (прошедших 14, будущих cliff-событий 0)
- Максимальное предложение по расписанию: 1 млрд

### Аллокация (доля финального предложения, %)

| Транш | сейчас | финал | разлочено |
|---|---|---|---|
| Public Distribution | 13.9 | 10 | 100% |
| Liquidity | 13.9 | 10 | 100% |
| Creators & CIC Digital 1 | 36 | 36 | 71.8% |
| Creators & CIC Digital 2 | 16.8 | 18 | 67.1% |
| Creators & CIC Digital 4 | 4 | 4 | 71.8% |
| Creators & CIC Digital 5 | 1.9 | 2 | 67.1% |
| Creators & CIC Digital 3 | 12.1 | 18 | 48.4% |
| Creators & CIC Digital 6 | 1.3 | 2 | 48.4% |

### Ближайшие разлоки (cliff)

Дискретных (cliff) событий впереди нет — для S5, который торгует именно обрывы, монета сейчас событий не даёт.
Это НЕ значит, что предложение не растёт: см. непрерывную эмиссию ниже.

### Непрерывная эмиссия (весь ряд `unlocked`)

Суммарный прирост разлоченного предложения по посуточному ряду — включает `linear`-транши, стейкинг-награды и майнинг, а не только обрывы:

| Горизонт | Прирост | % циркуляции |
|---|---|---|
| +30 дней | 27.11 млн | 10.35% |
| +90 дней | 81.32 млн | 31.05% |
| до конца расписания | 281.85 млн | 107.63% |

⚠️ Для монет с майнинговой или стейкинговой эмиссией эта таблица показывает инфляцию, а не разлоки. Событие S5 — только строка из таблицы cliff выше.

## Автосбор

**Да, полностью автоматизируемо.**

```
GET https://defillama-datasets.llama.fi/emissions/official-trump
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
