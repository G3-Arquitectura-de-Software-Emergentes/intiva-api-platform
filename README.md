# Intiva Platform

This API is made with Java, Spring Boot and Spring Data and MongoDB persistence.
It also uses Spring Security and JWT for authentication and authorization.

## Summary

This application is a backend API for the Intiva Platform, which is a platform for managing expenses, savings and incomes.
So, it includes the following features:

- Transaction management: Register and monitor all transactions, including expenses and incomes.
- Categorization: Classify transactions into different categories for better organization and analysis.
- Budget management: Set and track budgets for different categories of expenses and for different periods of time.
- Savings management: Set and track savings goals, and monitor progress towards those goals.
- Reminders and notifications: Set up reminders for upcoming bills, budget limits, and savings goals, and receive notifications to stay on track.
- Family group management: Create and manage family groups to share expenses, budgets, and savings goals among family members.
- Reporting and analytics: Generate reports and visualizations to analyze spending patterns, savings progress, and overall financial health.
- User management: Manage user accounts, including registration, authentication, and authorization.

## Technologies Used

For the development of this API, the following technologies were used:

### Main Stack

- Java 21: The programming language used for the development of the API.
- Spring Boot: A framework for building Java applications, used to create the API.
- Spring Data: A framework for data access, used to interact with the MongoDB database.
- PostgreSQL: A relational database management system, used for storing data in the application.
- Redis: An in-memory data structure store, used for caching and improving performance of the application.
- Spring Security: A framework for securing Java applications, used for authentication and authorization.
- JWT (JSON Web Tokens): A standard for securely transmitting information between parties, used for authentication and authorization.
- Swagger: A tool for documenting and testing RESTful APIs, used to create API documentation and provide an interface for testing the API.

### Testing

- JUnit: A testing framework for Java, used for unit testing the API.
- Mockito: A mocking framework for Java, used for creating mock objects in unit tests.
- Spring Test: A framework for testing Spring applications, used for integration testing the API.

### Approaches and Patterns

- RESTful API: The API follows REST principles for designing the endpoints and interactions.
- MVC (Model-View-Controller): The application is structured following the MVC pattern.
- Domain-Driven Design (DDD): The application is designed using DDD principles, focusing on the core domain and its logic.
- Layered Architecture: The application is organized into layers, such as the domain layer, infrastructure layer, application layer, and interfaces, to separate concerns and improve maintainability.
- Test-Driven Development (TDD): The development process follows TDD principles, where tests are written before the implementation of the functionality.

## Documentation

The project includes basic documentation and is available in the `docs` folder. It includes:

- C4 Model Software Architecture Diagrams: includes context, container and component level diagrams to provide an overview of the system architecture and its components.
- Class Diagrams: includes class diagrams to illustrate the structure of the codebase and the relationships between classes.

## Plan mensual con IA

Consulta la [guía de la PoC](poc/README.md) para levantar los servicios y probar el recorrido desde Android.
