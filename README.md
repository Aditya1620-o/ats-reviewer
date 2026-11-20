# ATS Reviewer Backend

A Java 21+ Spring Boot server built with Maven. The Maven Wrapper is included, so a separate Maven installation is not required.

## Prerequisites

- JDK 21 or newer
- Internet access on the first build so the Maven Wrapper can download Maven and project dependencies

Check your Java version:

```powershell
java -version
```

## Start the server

Run the command from the project root.

**Windows PowerShell:**

```powershell
.\mvnw.cmd spring-boot:run
```

**Git Bash, macOS, or Linux:**

```bash
./mvnw spring-boot:run
```

The server starts on port `8080`. Check its health at <http://localhost:8080/actuator/health>; a running server returns a status of `UP`. Stop the server with `Ctrl+C` in the terminal running it.

## Run tests

**Windows PowerShell:**

```powershell
.\mvnw.cmd test
```

**Git Bash, macOS, or Linux:**

```bash
./mvnw test
```

## Build and run the JAR

Build the executable JAR from PowerShell:

```powershell
.\mvnw.cmd package
```

Or from Git Bash, macOS, or Linux:

```bash
./mvnw package
```

Run the built JAR:

```powershell
java -jar target/ats-reviewer-backend-0.0.1-SNAPSHOT.jar
```

## Configuration

The default server port is `8080`. To use another port for a run, pass a Spring Boot property:

```powershell
java -jar target/ats-reviewer-backend-0.0.1-SNAPSHOT.jar --server.port=8081
```

The health endpoint will then be available at <http://localhost:8081/actuator/health>.

## Connect to Supabase PostgreSQL

1. In the Supabase dashboard, open **Project Settings > Database** and copy the **Session pooler** connection details. Use the session pooler for this long-running Spring Boot server.
2. Set `SUPABASE_DB_URL` in the root `.env` file, using the pooler host and project-specific username shown by Supabase:

	```dotenv
	SUPABASE_DB_URL=jdbc:postgresql://<session-pooler-host>:5432/postgres?sslmode=require&user=postgres.<project-ref>&password=<url-encoded-password>
	```

	Replace the placeholders with your project's values. URL-encode the database password if it contains reserved URL characters. `.env` is ignored by Git; do not commit database credentials.
3. Start the server using the command in [Start the server](#start-the-server). Spring Boot reads `.env` automatically.

The connection uses JDBC. Flyway applies the PostgreSQL schema from `src/main/resources/db/migration` at startup. Tests use an in-memory H2 database and do not require Supabase credentials; PostgreSQL migrations are disabled for the H2 test profile.