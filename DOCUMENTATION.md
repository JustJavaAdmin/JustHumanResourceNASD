# JustHumanResource Technical Documentation

## 1. Executive Summary
JustHumanResource is a comprehensive Human Resource Management System (HRMS) built using modern Java technologies. The system provides a robust platform for managing employee lifecycles, payroll processing, performance appraisals (KPIs), and various HR workflows. It leverages enterprise-grade tools like Flowable for process orchestration and Keycloak for secure identity and access management.

## 2. Technology Stack
*   **Backend:** Java 21, Spring Boot 3.5.3
*   **Database:** PostgreSQL
*   **Workflow Engine:** Flowable 7.1.0
*   **Security:** Keycloak (OAuth2/OpenID Connect)
*   **Frontend:** Thymeleaf, HTMX, Bootstrap 5.3
*   **API Documentation:** SpringDoc OpenAPI (Swagger)
*   **AI Integration:** Spring AI (OpenAI)
*   **Payment Gateway:** Paystack
*   **Build & Deployment:** Maven, Jib (Containerization)

## 3. System Architecture
The application follows a modular monolith architecture. Each business domain is encapsulated within its own package, promoting separation of concerns and maintainability.

### Core Architecture Components:
*   **Controllers:** Handle HTTP requests and interact with services.
*   **Services:** Implement business logic and orchestrate data flow between repositories and other components.
*   **Repositories:** Spring Data JPA interfaces for database interaction.
*   **Entities:** JPA models representing the database schema.
*   **DTOs & Mappers:** Data Transfer Objects for API/Web layer, with MapStruct for efficient mapping.
*   **Workflows:** BPMN 2.0 processes managed by Flowable for complex multi-step HR operations.

## 4. Module Overview

### 4.1. Human Resources (HR)
Managed under the `com.justjava.humanresource.hr` package.
*   **Employee Management:** Handles personal details, bank accounts, documents, and position history.
*   **Organization Structure:** Manages Departments, Job Grades, Job Steps, and Pay Groups.
*   **Onboarding:** Orchestrates the new hire process through a dedicated Flowable workflow.

### 4.2. Payroll Management
Managed under the `com.justjava.humanresource.payroll` package.
*   **Components:** Defines Allowances, Deductions, and Tax Reliefs.
*   **Processing:** Supports payroll cycles, period closing, and automated payroll runs.
*   **Reports:** Generates Pay Slips, Payroll Audit Logs, and Reconciliation Reports.
*   **Integration:** Uses Paystack for payment processing.

### 4.3. Workflow Management
Managed under the `com.justjava.humanresource.workflow` package.
*   **Flowable Integration:** A custom `FlowableTaskService` provides a simplified API for interacting with the Flowable engine.
*   **Key Processes:**
    *   `onboardingProcess`: Manages new employee entry.
    *   `payrollApproval`: Multi-stage approval for payroll runs.
    *   `employeeAppraisalProcess`: Performance review cycle.
    *   `payrollPeriodCloseProcess`: End-of-month processing.

### 4.4. Performance Management (KPI)
Managed under the `com.justjava.humanresource.kpi` package.
*   Supports monthly and quarterly appraisal cycles.
*   Orchestrates reassignment and evaluation processes via BPMN workflows.

### 4.5. Security & Authentication
*   **Keycloak:** Used as the Identity Provider (IdP).
*   **OAuth2:** Secure authentication for both Web and Mobile clients.
*   **Multi-Realm Support:** Configured for different authentication realms (e.g., `humanResources-nasd`, `employees-nasd`).

## 5. Key Workflows (BPMN)
The project includes several critical business processes defined in `src/main/resources/processes`:
*   **Batch Payroll Process:** Handles large-scale payroll calculations.
*   **Onboarding Process:** Steps for integrating new employees.
*   **KPI Evaluation:** Automated performance review stages.
*   **Payroll Approval:** Ensures compliance and oversight before disbursement.

## 6. Setup and Deployment

### 6.1. Prerequisites
*   JDK 21
*   PostgreSQL
*   Keycloak Server
*   Maven

### 6.2. Configuration
Configuration is managed via `application.yml` and environment variables. Key variables include:
*   `JDBC_DATABASE_URL`
*   `ISSUER_URI` (Keycloak)
*   `openaiAPI_KEY`
*   `KEYCLOAK_CLIENT_SECRET`

### 6.3. Building the Application
```bash
./mvnw clean package
```

### 6.4. Containerization
The project uses Jib to build OCI-compliant images without a Docker daemon:
```bash
./mvnw jib:build
```

## 7. API Endpoints
Comprehensive API documentation is available at `/swagger-ui.html` when the application is running, covering all modules including HR, Payroll, and Workflows.
