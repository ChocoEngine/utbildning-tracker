# Иконка приложения

[Первый лист концептов](ink-icon-concepts-v1.png): **выбран левый вариант** — единая мягкая асимметричная клякса с диагональными полосами: зелёной, терракотовой и сиреневой, на тёплом светлом фоне. Без текста и дополнительных символов. Средний и правый варианты не используются.

Выбранный дизайн подключён в Android launcher. Чистый векторный исходник — [ink-launcher.svg](ink-launcher.svg); он вручную воспроизводит левый вариант без рамки. Цвета: #78AC80 / #DC9475 / #9D95CC, фон #FCFAF6.

S24 в [MVP_PLAN.md](../../MVP_PLAN.md) выполнен частично: ресурсы подключены, сборка/lint прошли; на API 36 проверены список приложений, рабочий стол, круговая маска и тематические иконки. Скруглённая квадратная маска на установленном launcher недоступна и не проверена. [Снимки и результаты](../../docs/checks/s24/README.md).

Лист концептов создан встроенным инструментом image_gen. Launcher использует отдельные VectorDrawable переднего плана/монохромного силуэта и сплошной фон; `ic_launcher` и `ic_launcher_round` в `mipmap-anydpi-v26/`, manifest с `android:icon` / `android:roundIcon`. Весь лист вариантов не используется.

## Промпт генерации

Use case: logo-brand. Create one polished concept presentation sheet showing three proposed Android app icons for an offline study course tracker, whose motivating visual is a calendar filled with multicolored ink blobs. Landscape sheet, three equally sized rounded-square warm ivory (#fcfaf6) icon tiles in a row on a subtly darker warm neutral background. Each icon is a simple flat vector-style organic ink blot, friendly smooth asymmetric rounded outline, no sharp splatter spikes or detached drops. Use exactly three muted but legible colors sage green #78AC80, terracotta #DC9475, lavender #9D95CC. Left icon: single compact organic blob divided into three wide diagonal stripes with clean adjoining edges. Middle: three softly overlapping organic colored blobs forming one compact unified mark. Right: slightly more wavy organic blot with three broad horizontal flowing bands. Generous safe margins inside each tile, central silhouette occupying 60 percent of tile width; readable as a tiny launcher icon. No books, calendar grid, checkmarks, letters, labels, numbers, gradients, 3D, gloss, textures, watermarks, or decorative surroundings. Calm warm minimal design, consistent optical size. This sheet is for design approval, not a finished launcher asset.
