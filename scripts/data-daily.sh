#!/bin/bash
# Задание Windows crypto-data-daily (25.10.2026): забор на локальный диск, дни стенда, потом проверка и отчёт в Telegram.
cd "$(dirname "$0")/.." || exit 1
bash scripts/data-pull.sh
# 17.11.2026: дни стенда (DayStore) из свежих копий; не невосполнимое — лог рядом с днями, ошибка ловится проверкой
JAR=/d/revx-days/tools.jar bash scripts/days-build.sh "$(date -d '-5 days' +%F)" >> /d/revx-days/build.log 2>&1
# Страховочный проход LZX по всем рабочим дням: у WOF-сжатия нет наследования от папки, поэтому всё, что
# попало в D:\revx-days не через days-build.sh (руками, восстановлением), сожмётся здесь. Сжатые файлы
# пропускаются — секунды. ⚠️ archive\*.tgz и tools.jar не трогаем: первый уже сжат, второй перезаписывается.
for d in /d/revx-days/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]; do
  [ -d "$d" ] && cmd //c "compact /c /exe:lzx /s:$(cygpath -w "$d") /i /q" > /dev/null
done
echo "$(date +%F' '%T) страховочный LZX-проход сделан" >> /d/revx-days/build.log
# Вывод прогонов стенда (журналы j-* и логи): сжимаются LZX файлы старше двух суток — прогон к тому времени
# кончился. Журналы SQLite ужимаются ~2.5×, логи сильнее; читать как прежде, а перезапись молча распакует.
for out in ${STAND_OUT:-C:/revx-xrp/hp D:/revx-data/hp-j}; do
  powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w scripts/lzx-old.ps1)" -Dir "$(cygpath -w "$out")" -Days 2 >> /d/revx-days/build.log 2>&1
done
bash scripts/data-check.sh
