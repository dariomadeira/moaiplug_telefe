# Moai Telefe — plugin .dex (Contrato moai v1)

Primer plugin del motor de moai3. Aporta el canal **11 (TELEFE, Argentina)** al
catálogo. El plugin **solo resuelve** la señal (porta el `MiTelefeScraper` de
moaiServer); el motor de moai3 la reproduce tal cual.

## Cómo resuelve la señal (canal_11)

1. `GET https://www.mitelefe.com/vivo` (UA móvil) → extrae `LAMBDA_URL` y
   `STREAM_ID` del HTML (usa valores por defecto si el scrape falla).
2. `GET <LAMBDA_URL>?stream_id=<STREAM_ID>` → JSON con `access_token` y
   `player_id`.
3. Entrega
   `https://mdstrm.com/live-stream-playlist/<STREAM_ID>.m3u8?player=<PLAYER_ID>&access_token=<TOKEN>`
   con headers `Referer`/`Origin: https://www.mitelefe.com` y formato `hls`.
   El host de mdstrm responde un 302 al master real en `cdn.mdstrm.com`
   (HLS multi-variante, token en query).

Resolución cacheada 5 minutos (los tokens de mdstrm son de corta vida);
`fallbackIndex > 0` fuerza re-resolución fresca. Si no hay
`access_token` o `player_id`, o mdstrm responde `401 CLOSED_ACCESS`, `resolve()`
lanza excepción y la app reporta el error (reabrir el canal dispara
fallbackIndex = 1 → token nuevo).

## Instalación en moai3

Desde **Fuentes** en la app, pegar cualquiera de estas URLs (HTTPS):

- `https://raw.githubusercontent.com/dariomadeira/moaiplug_telefe/main/manifest.json`
- `https://raw.githubusercontent.com/dariomadeira/moaiplug_telefe/main/plugin.dex`

El host deriva el archivo gemelo automáticamente y verifica el `sha256`
siempre-on del dex contra `manifest.json`.

## Construcción

```bash
./build.sh
```

Genera `plugin.dex` y `manifest.json` (con `sha256`) en la raíz. Requiere
JDK 8+ y Android SDK (avanzado por `ANDROID_HOME` o `~/Android/Sdk`, default
build-tools 36.0.0).

### Autotest en JVM

```bash
java -cp build/plugin:build/contract com.infomak.moai.telefe.TelefePlugin
```

Resuelve la señal en vivo e imprime el resultado (necesita internet).

## Contrato

Compila contra `com.infomak.moai.contract` (stubs Java en
`src/contract/java/`). En runtime las clases del contrato las provee la app
(parent-first classloader); **no viajan en el .dex**. La clase del plugin es
`com.infomak.moai.telefe.TelefePlugin` (constructor público sin argumentos).

| Campo         | Valor                                                     |
|---------------|-----------------------------------------------------------|
| id            | `moai_telefe`                                             |
| version       | `1.0.0` (coincide con el tag `v1.0.0`)                    |
| minContrato   | 1                                                         |
| maxContrato   | 1                                                         |
| canal         | `canal_11` → Canal 11 (Argentina)                         |
| formato       | `hls`                                                     |