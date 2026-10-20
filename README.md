# Sistema de Generación Asíncrona de Reportes de Ventas

Sistema distribuido para la generación asíncrona de reportes de ventas masivos en formato CSV, construido con Spring Boot, RabbitMQ (CloudAMQP) y PostgreSQL.

---

## Tecnologías Utilizadas

- Lenguaje/Framework: Java 17, Spring Boot 3 (Spring Web, Spring Data JPA, Spring AMQP)
- Broker de Mensajes: RabbitMQ (instancia alojada en CloudAMQP)
- Base de Datos: PostgreSQL 16 (10,000 registros sintéticos sembrados)
- Frontend: HTML5, JavaScript (Fetch API, polling de estado), Bootstrap 5

---

## Arquitectura del Sistema

El sistema separa la recepción de peticiones HTTP del procesamiento pesado en segundo plano:

1. API REST: Recibe la solicitud (POST /reports), valida parámetros/fechas, persiste el registro inicial en estado queued en PostgreSQL y publica el trabajo en la cola de RabbitMQ. Responde de inmediato con HTTP 202 Accepted.
2. RabbitMQ: Cola durable (reports_queue) que almacena las tareas pendientes con persistencia de mensajes y confirmaciones de publicador.
3. Report Worker: Consumidor asíncrono con confirmación manual (manual ACK). Procesa la consulta sobre PostgreSQL, genera el archivo CSV en almacenamiento y actualiza el estado a completed. Maneja reintentos limitados (máximo 3) ante fallos transitorios.
4. Base de Datos y Almacenamiento: PostgreSQL guarda el catálogo de ventas y el historial del ciclo de vida del reporte. Los archivos CSV procesados se almacenan localmente para su descarga.

---

## Prerrequisitos e Instalación

### 1. Requisitos Previos
- Java JDK 17 o superior.
- PostgreSQL 15+ instalado y ejecutándose.
- Instancia activa de RabbitMQ (local o cuenta en CloudAMQP).

### 2. Configuración de Variables de Entorno
Crea un archivo .env en la raíz (puedes tomar como referencia .env.example):

SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/sales_reports_db
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=tu_contraseña

SPRING_RABBITMQ_HOST=shark.rmq.cloudamqp.com
SPRING_RABBITMQ_PORT=5672
SPRING_RABBITMQ_USERNAME=vlyngjgh
SPRING_RABBITMQ_PASSWORD=tu_password_cloudamqp
SPRING_RABBITMQ_VIRTUAL_HOST=vlyngjgh

### 3. Población de Base de Datos (Sembrado de 10,000 registros)
Ejecuta el script SQL incluido en src/main/resources/schema.sql o el componente automático de sembrado DataSeeder.java para poblar la tabla sales con los 10,000 registros de ventas requeridos.

### 4. Compilación y Ejecución
- Compilar el proyecto: `./mvnw clean package -DskipTests`
- Ejecutar la aplicación: `./mvnw spring-boot:run`

La interfaz web estará disponible en http://localhost:8080/.

---

## Documentación de la API

### 1. Solicitar Generación de Reporte
- Endpoint: POST /reports
- Headers:
  - Content-Type: application/json
  - Idempotency-Key: <clave_unica> (Opcional / Recomendado)
- Body:
{
  "startDate": "2025-01-01",
  "endDate": "2025-12-31"
}
- Respuesta (202 Accepted):
{
  "id": 1,
  "status": "queued",
  "startDate": "2025-01-01",
  "endDate": "2025-12-31",
  "retryCount": 0,
  "exportedRows": 0,
  "downloadUrl": null
}

### 2. Consultar Estado del Reporte
- Endpoint: GET /reports/{id}
- Respuesta (200 OK):
{
  "id": 1,
  "status": "completed",
  "retryCount": 1,
  "exportedRows": 5097,
  "downloadUrl": "/reports/1/download"
}

### 3. Descargar Archivo CSV
- Endpoint: GET /reports/{id}/download
- Respuesta: Descarga directa del archivo reporte_ventas_1.csv (HTTP 200) o HTTP 409 si aún no está listo.

---

## Modelo de Datos y Esquema de Mensajes

### Tabla PostgreSQL: reports
- id (BIGINT, PK, Auto-increment)
- start_date (DATE)
- end_date (DATE)
- status (VARCHAR) - queued, processing, retrying, completed, failed
- retry_count (INT)
- exported_rows (INT)
- file_url (VARCHAR)
- idempotency_key (VARCHAR, Unique Index)
- error_message (TEXT)
- created_at / updated_at (TIMESTAMP)

### Mensaje RabbitMQ (reports_queue)
{
  "reportId": 1,
  "startDate": "2025-01-01",
  "endDate": "2025-12-31",
  "attemptNumber": 1
}

---

## Escenarios de Prueba Validados

| Caso | Escenario de Prueba | Comportamiento Esperado | Evidencia |
| :---: | :--- | :--- | :--- |
| 1 | Fallo Transitorio / Reintentos | Reintenta ante excepciones temporales y completa en menos de 3 intentos. | Evidencia_Caso1_Reintentos.png |
| 2 | Fallo Permanente | Agota los 3 reintentos y marca el estado como failed. | Evidencia_Caso2_Fallo_Permanente.png |
| 3 | Rango de Fechas Inválido | Bloqueo inmediato en API con HTTP 400 sin encolar en RabbitMQ. | Evidencia_Caso3_Fechas_Invalidas.png |
| 4 | Sin Coincidencias | Finaliza en completed exportando 0 filas (solo encabezados). | Evidencia_Caso4_Sin_Coincidencias.png |
| 5 | Idempotencia | Solicitudes duplicadas con la misma clave devuelven la tarea existente. | Evidencia_Caso5_Idempotencia.png |

Nota para Pruebas de Fallo: Para simular una falla que fuerce el circuito de reintentos, configure la fecha de inicio con el año 2000 (2000-01-01).

---

## Comparativa de Rendimiento (1 vs. 3 Workers)

| Métrica | 1 Worker | 3 Workers (Concurrente) | Escalabilidad / Resultado |
| :--- | :---: | :---: | :---: |
| Tiempo Total (20 reportes) | ~100 seg | ~37 seg | 2.7x más rápido |
| Tasa de Procesamiento | 12 rep/min | 32.4 rep/min | +170% capacidad de respuesta |

La ganancia no es perfectamente 3x debido a la contención de I/O en disco al escribir archivos CSV concurrentes y la latencia de red al enviar confirmaciones manuales (ACK) a CloudAMQP.

---

## Respuestas a Preguntas de Diseño

1. ¿Por qué el procesamiento asíncrono es apropiado aquí?
Evita bloquear el servidor HTTP web (Tomcat) durante consultas pesadas en base de datos e I/O de archivos, garantizando respuestas inmediatas (202 Accepted) y previniendo errores por timeout.

2. ¿Qué almacena RabbitMQ y qué pertenece a PostgreSQL?
RabbitMQ guarda únicamente mensajes efímeros de trabajo pendientes de procesamiento. PostgreSQL guarda la veracidad del dominio: datos de ventas, el historial completo del ciclo de vida de los reportes y las claves de idempotencia.

3. ¿En qué se diferencian los Publisher Confirms de los Consumer Acknowledgment?
Publisher Confirms le garantizan a la API que RabbitMQ recibió y encoló el mensaje. Consumer ACKs le aseguran a RabbitMQ que el trabajador procesó, guardó el CSV y actualizó la DB antes de eliminar la tarea de la cola.

4. ¿Qué sucede si el trabajador termina el archivo pero se cae antes de enviar el ACK?
RabbitMQ reencola el mensaje no confirmado. Al tomarlo otro trabajador, este verifica el estado en PostgreSQL (status == completed), detecta que fue terminado y emite el ACK sin duplicar el trabajo.

5. ¿Pueden procesarse los mensajes más de una vez?
Sí, en arquitecturas distribuidas la garantía de entrega es at-least-once. Por ello, los trabajadores implementan lógica idempotente verificando estados en base de datos.

6. ¿La entrega FIFO garantiza orden de finalización con varios trabajadores?
No. La entrega a los trabajadores es ordenada, pero el tiempo de ejecución de cada tarea depende del tamaño del rango de fechas y la carga del hilo asignado.

7. ¿Cómo evitas que dos trabajadores corrompan el mismo reporte?
Utilizando transacciones atómicas en PostgreSQL para cambiar el estado de queued a processing mediante un bloqueo optimista o pesimista.

8. ¿Qué sucede si las solicitudes llegan más rápido de lo que los trabajadores procesan?
RabbitMQ amortigua la carga almacenando los mensajes de forma persistente en cola, implementando un mecanismo natural de backpressure.

9. ¿Qué ventanas de fallo persisten en esta implementación?
La ventana entre guardar el registro queued en la DB y la confirmación de entrega en RabbitMQ (resolvible mediante el patrón Transactional Outbox).

10. ¿Cómo evolucionarías este diseño para múltiples máquinas?
Migrar el almacenamiento local de CSV a un servicio de objetos distribuido (Amazon S3), desplegar RabbitMQ en un clúster con Quorum Queues y escalar horizontalmente los trabajadores mediante contenedores Docker en Kubernetes.

---

## Declaración de Contribución y Uso de IA

- Integrantes: Desarrollo completo de la arquitectura API, configuración del Broker CloudAMQP, Worker de procesamiento, persistencia en PostgreSQL, interfaz web y batería de pruebas de tolerancia a fallos.
- Uso de IA: Se utilizaron herramientas de IA como apoyo para la depuración de la configuración de Spring AMQP (ACKs manuales), la estructuración de respuestas técnicas del informe y la creación de scripts de simulación de carga. Todos los resultados fueron validados y ejecutados manualmente en el entorno local.
