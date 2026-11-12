# PoC: plan mensual con IA

Esta prueba ejecuta AI Agent, Finances y Communications en procesos independientes, detrás de Spring Cloud Gateway. Los tres comparten un artefacto Spring Boot para simplificar la compilación local, pero cada proceso activa solo su perfil y usa su propia base PostgreSQL. El backend anterior mantiene login, cuentas, categorías y registro manual mientras se migran esos contextos.

## Ejecutar en local

1. Copiar `.env.poc.example` a `.env.poc`. Completar las contraseñas con valores locales distintos; usar una clave interna de al menos 24 caracteres, una clave JWT compatible con IAM y una clave de Gemini. La clave de Gemini solo se configura en el servicio agent.
2. Ejecutar `docker compose --env-file .env.poc -f compose.poc.yml up --build`. El Gateway escucha en `127.0.0.1:8080`. Los demás servicios no publican puertos. El perfil local deja desactivados FCM y el uso de Cloudinary sin credenciales; la prueba utiliza correo y contraseña y avisos dentro de la app.
3. Consultar `GET /health`. En Android, el emulador usa `http://10.0.2.2:8080/api/v1/`. Entrar con una cuenta de prueba y registrar al menos un gasto personal en PEN, con una categoría activa, en alguno de los tres meses anteriores al plan.
4. En Inicio, abrir **Agente · Plan mensual**. Generar el borrador, guardar una edición y aprobar la versión mostrada. Consultar el estado y el aviso: el outbox publica el evento después de la persistencia y Communications lo registra de forma asíncrona.
5. Probar la categorización en el registro de gastos personales. Si Gemini no devuelve una categoría válida, el usuario puede continuar manualmente.

## Contrato

Todos los endpoints del Gateway requieren el token de la sesión para acceder a datos del agente y avisos. El servidor resuelve el titular a partir del token y nunca lo toma del cuerpo de la solicitud.

| Método y ruta | Solicitud | Respuesta |
| --- | --- | --- |
| POST /api/v1/category-suggestions | `description`, `amount`, `currency=PEN` | `SUGGESTED` con ID y nombre del catálogo activo, o `UNAVAILABLE`. No registra un gasto. |
| GET /api/v1/financial-insights?month=AAAA-MM | Mes | Cantidad y total de gastos de hasta S/ 10. |
| POST /api/v1/monthly-plans | `month` | Borrador con ID, versión, explicación y propuesta de límite. |
| PATCH /api/v1/monthly-plans/{id} | `version`, `proposedLimit` | Borrador con nueva versión, o 409 si la versión no coincide. |
| POST /api/v1/monthly-plans/{id}/approve | `version` | Estado de aplicación. Repetir la aprobación de la misma versión es seguro. |
| GET /api/v1/monthly-plans/{id} | — | Estado y resultado del límite. |
| GET /api/v1/plan-notifications | — | Avisos del titular, cada uno con el ID del plan. |

El agente usa una herramienta de lectura con proyecciones agregadas y valida la salida de Gemini. La aprobación de Android ejecuta la operación de escritura determinista, mediante la API privada de Finances. La clave del servicio no se distribuye al cliente. El guardado y el outbox quedan en la misma transacción; la publicación espera confirmación y Communications descarta duplicados por ID.

La PoC persiste un límite mensual por categoría en Finances. Este límite se muestra en el flujo del plan; el listado heredado de límites todavía no consume esta base. Aportes, pagos, recordatorios programados, seguimiento automático y entrega push quedan fuera de la PoC. Los avisos se consultan en la app. La proyección lee gastos personales autorizados del backend anterior hasta que se extraiga Analytics.

## Pruebas

`mvn -f poc/pom.xml test` compila el módulo. Para ejecutar la prueba de persistencia, definir `INTIVA_TEST_DB` con una URL JDBC de una base PostgreSQL exclusiva para pruebas, y `INTIVA_TEST_DB_USER` y `INTIVA_TEST_DB_PASSWORD`. La prueba simula pérdida de confirmación después de guardar y una entrega repetida al consumidor, sin llamar a Gemini ni requerir RabbitMQ.
