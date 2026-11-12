# PoC: plan mensual con IA

Esta prueba ejecuta AI Agent, Finances, Savings y Communications en procesos independientes, detrás de Spring Cloud Gateway. Los servicios comparten un artefacto Spring Boot para simplificar la compilación local, pero cada proceso activa solo su perfil y usa su propia base PostgreSQL. El backend anterior mantiene login, cuentas, categorías y registro manual mientras se migran esos contextos.

## Ejecutar en local

1. Instalar y arrancar [Docker Desktop](https://docs.docker.com/desktop/setup/install/windows-install/). Desde la raíz, ejecutar `pwsh -File poc/Initialize-LocalEnvironment.ps1` para crear `.env.poc` con claves locales distintas. Crear una [clave de Gemini](https://ai.google.dev/gemini-api/docs/api-key) y completar `GEMINI_API_KEY` en ese archivo. No copiar el archivo al repositorio ni pegar claves en el chat.
2. Ejecutar `docker compose --env-file .env.poc -f compose.poc.yml up --build`. El Gateway escucha en `127.0.0.1:8080`. Los demás servicios no publican puertos. El perfil local deja desactivados FCM y el uso de Cloudinary sin credenciales; la prueba utiliza correo y contraseña y avisos dentro de la app.
3. Consultar `GET /health`. En Android, el emulador usa `http://10.0.2.2:8080/api/v1/`. Entrar con una cuenta de prueba y registrar al menos un gasto personal en PEN, con una categoría activa, en alguno de los tres meses anteriores al plan.
4. En Inicio, abrir **Agente · Plan mensual**. Generar el borrador, editar el límite y, si deseas, añadir un aporte previsto y un recordatorio. Guarda los cambios y aprueba esa versión. Para probar el aporte, crea previamente una meta personal activa en PEN. Consultar el estado y el aviso: el outbox publica el evento después de la persistencia y Communications lo registra de forma asíncrona.
5. Probar la categorización en el registro de gastos personales. Si Gemini no devuelve una categoría válida, el usuario puede continuar manualmente.

## Contrato

Todos los endpoints del Gateway requieren el token de la sesión para acceder a datos del agente y avisos. El servidor resuelve el titular a partir del token y nunca lo toma del cuerpo de la solicitud.

| Método y ruta | Solicitud | Respuesta |
| --- | --- | --- |
| POST /api/v1/category-suggestions | `description`, `amount`, `currency=PEN` | `SUGGESTED` con ID y nombre del catálogo activo, o `UNAVAILABLE`. No registra un gasto. |
| GET /api/v1/financial-insights?month=AAAA-MM | Mes | Cantidad y total de gastos de hasta S/ 10. |
| POST /api/v1/monthly-plans | `month` | Borrador con ID, versión, explicación y propuesta de límite. |
| GET /api/v1/planning-goals | — | Metas personales activas en PEN. |
| PATCH /api/v1/monthly-plans/{id} | `version`, `proposedLimit` y, opcionalmente, `goalId` con `contribution`, o `reminderDate` con `reminderLabel` | Borrador con nueva versión, o 409 si la versión no coincide. |
| POST /api/v1/monthly-plans/{id}/approve | `version` | Estado de aplicación. Repetir la aprobación de la misma versión es seguro. |
| GET /api/v1/monthly-plans/{id} | — | Estado y resultado del límite. |
| GET /api/v1/plan-notifications | — | Avisos del titular, cada uno con el ID del plan. |

El agente usa una herramienta de lectura con proyecciones agregadas y valida la salida de Gemini. La aprobación de Android ejecuta la operación de escritura determinista, mediante la API privada de Finances. La clave del servicio no se distribuye al cliente. El guardado y el outbox quedan en la misma transacción; la publicación espera confirmación y Communications descarta duplicados por ID.

La PoC persiste un límite mensual por categoría en Finances. Este límite se muestra en el flujo del plan; el listado heredado de límites todavía no consume esta base. Savings registra aportes previstos asociados a una meta personal, sin modificar el saldo ni contabilizar un aporte real. Communications programa recordatorios en la zona de Lima y crea un aviso al vencer. El puente de transacciones del backend anterior reenvía gastos personales nuevos en PEN; Finances evita procesarlos dos veces y emite un aviso al alcanzar el límite. El seguimiento no importa ni recalcula gastos anteriores, ediciones o anulaciones. Los avisos se consultan en la app; el envío push por FCM y la generación automática de un nuevo plan al cierre quedan pendientes. La proyección lee gastos personales autorizados del backend anterior hasta extraer Analytics.

## Pruebas

`mvn -f poc/pom.xml test` compila el módulo. Para ejecutar la prueba de persistencia, definir `INTIVA_TEST_DB` con una URL JDBC de una base PostgreSQL exclusiva para pruebas, y `INTIVA_TEST_DB_USER` y `INTIVA_TEST_DB_PASSWORD`. La prueba simula pérdida de confirmación después de guardar y una entrega repetida al consumidor, sin llamar a Gemini ni requerir RabbitMQ.


## Configuración de Android y publicación

- En el proyecto Firebase de Intiva, registrar o seleccionar `com.resolum.intiva` y descargar [google-services.json](https://firebase.google.com/docs/android/setup). Colocarlo en `app/` del repositorio móvil. Para local, usar el emulador y compilar `assembleDebug`.
- Para distribuir una versión, configurar la variable `INTIVA_GATEWAY_URL` con la dirección HTTPS del Gateway y los secretos de firma y Firebase ya referenciados por el workflow de App Distribution. La aplicación debe pasar primero la prueba local.
- Para Azure, se necesita una suscripción con permisos de despliegue y presupuesto. Crear el entorno de [Azure Container Apps](https://learn.microsoft.com/azure/container-apps/quickstart-portal), PostgreSQL y un RabbitMQ accesible de forma privada. Publicar el Gateway como única entrada externa y configurar secretos, conectividad y bases por servicio antes de desplegar. Este documento no aprovisiona recursos ni habilita facturación.
- Si ya existe un volumen de la versión anterior de la PoC, el script de inicialización del contenedor no vuelve a ejecutarse. Provisionar `savings_db` y su rol en ese servidor antes de iniciar Savings, o usar un volumen nuevo para pruebas. No borrar el volumen anterior para actualizar.

Para verificar localmente: confirmar que repetir la aprobación no duplica límite, aporte ni recordatorio; registrar un gasto nuevo que cruce el límite y comprobar un solo aviso; probar además una meta que no pertenezca al usuario y un plan con versión obsoleta.
