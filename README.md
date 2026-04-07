## **Backend README (Spring Boot)**

```markdown
# LamaStudio Backend 🎬

[LamaStudio](src/main/resources/static/og-image.jpg)

RESTful API backend for LamaStudio streaming platform built with Spring Boot. Handles user authentication, content management, and streaming services.

## 🚀 Features

- **🔐 JWT Authentication**: Secure user authentication and authorization
- **👥 User Management**: User profiles, roles, and preferences
- **🎥 Content Management**: Movies, TV shows, episodes management
- **📊 Streaming Analytics**: Track views and user engagement
- **💬 Reviews & Ratings**: User-generated content ratings
- **🔍 Advanced Search**: Filter and search content
- **📈 Trending Algorithm**: Smart content recommendation
- **📦 Pagination**: Efficient data loading

## 🛠️ Tech Stack

- **Framework**: Spring Boot 3.x
- **Language**: Java 17
- **Database**: PostgreSQL
- **ORM**: Spring Data JPA (Hibernate)
- **Security**: Spring Security with JWT
- **API Documentation**: SpringDoc OpenAPI (Swagger)
- **Build Tool**: Maven
- **Testing**: JUnit 5, Mockito
- **Caching**: Redis
- **File Storage**: AWS S3 / Local filesystem
- **Containerization**: Docker

## 📁 Project Structure
lamastudio-backend/
├── src/
│ ├── main/
│ │ ├── java/com/lamastudio/
│ │ │ ├── config/ # Configuration classes
│ │ │ ├── controller/ # REST controllers
│ │ │ ├── dto/ # Data Transfer Objects
│ │ │ ├── exception/ # Exception handling
│ │ │ ├── model/ # Entity models
│ │ │ ├── repository/ # Data repositories
│ │ │ ├── security/ # Security configuration
│ │ │ ├── service/ # Business logic
│ │ │ ├── util/ # Utility classes
│ │ │ └── LamaStudioApplication.java
│ │ └── resources/
│ │ ├── application.yml # Application config
│ │ ├── db/migration/ # Flyway migrations
│ │ └── static/ # Static resources
│ └── test/ # Unit and integration tests
├── docker-compose.yml # Docker setup
├── pom.xml # Maven dependencies
└── README.md

text

## 🚦 Getting Started

### Prerequisites

- Java 17 or higher
- Maven 3.8+
- PostgreSQL 14+
- Docker (optional)

### Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/Tinniestudio/lamastudio_backend.git
   cd lamastudio_backend
Configure the database:

sql
CREATE DATABASE lamastudio;
CREATE USER lamastudio_user WITH PASSWORD 'your_password';
GRANT ALL PRIVILEGES ON DATABASE lamastudio TO lamastudio_user;
Update application.yml:

yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/lamastudio
    username: lamastudio_user
    password: your_password
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true

app:
  jwt:
    secret: your-jwt-secret-key
    expiration: 86400000

file:
  upload-dir: ./uploads
Build and run:

bash
mvn clean install
mvn spring-boot:run
The API will be available at http://localhost:8080/api

📚 API Documentation
Once running, access Swagger UI at:

text
http://localhost:8080/swagger-ui.html
Main Endpoints
Method	Endpoint	Description	Auth Required
POST	/api/auth/login	User login	No
POST	/api/auth/register	User registration	No
GET	/api/movies	Get all movies	Yes
GET	/api/movies/{id}	Get movie by ID	Yes
POST	/api/movies	Create movie	Admin
PUT	/api/movies/{id}	Update movie	Admin
DELETE	/api/movies/{id}	Delete movie	Admin
GET	/api/shows	Get all TV shows	Yes
POST	/api/reviews	Add review	Yes
GET	/api/users/profile	Get user profile	Yes
🐳 Docker Setup
Run with Docker Compose:

bash
docker-compose up -d
Example docker-compose.yml:

yaml
version: '3.8'
services:
  postgres:
    image: postgres:14
    environment:
      POSTGRES_DB: lamastudio
      POSTGRES_USER: lamastudio_user
      POSTGRES_PASSWORD: your_password
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data

  redis:
    image: redis:7
    ports:
      - "6379:6379"

  app:
    build: .
    ports:
      - "8080:8080"
    depends_on:
      - postgres
      - redis
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/lamastudio
      SPRING_DATASOURCE_USERNAME: lamastudio_user
      SPRING_DATASOURCE_PASSWORD: your_password

volumes:
  postgres_data:

## Email (Resend) configuration

This project uses Resend (https://resend.com) for sending transactional emails (verification, password reset).

Configuration is available via environment variables or the `application.yml` under `app.resend`:

- RESEND_API_KEY — your Resend API key (recommended to set in environment)
- RESEND_FROM_EMAIL — optional default "from" email address (falls back to `no-reply@tinniestudio.com.com`)

In `application.yml` the properties are:

app:
  resend:
    api-key: ${RESEND_API_KEY:}
    from-email: ${RESEND_FROM_EMAIL:no-reply@yourdomain.com}

If you previously used SMTP (JavaMailSender), that dependency and configuration have been removed: the app now sends mail via the Resend HTTP API. During development you can still stub/mock the email service in tests.

🧪 Testing
Run tests with:

bash
mvn test                    # Run unit tests
mvn integration-test        # Run integration tests
mvn verify                  # Run all tests with coverage
🔒 Security Configuration
java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors().and().csrf().disable()
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/public/**").permitAll()
                .requestMatchers("/swagger-ui/**").permitAll()
                .anyRequest().authenticated()
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .addFilterBefore(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);
        
        return http.build();
    }
}
📦 Dependencies (pom.xml)
Key dependencies include:

Spring Boot Starter Web

Spring Boot Starter Data JPA

Spring Boot Starter Security

PostgreSQL Driver

JJWT for JWT handling

Lombok

MapStruct

SpringDoc OpenAPI

Flyway for migrations

🗄️ Database Schema
Main tables:

users - User accounts

roles - User roles

movies - Movie metadata

tv_shows - TV show metadata

episodes - Individual episodes

reviews - User reviews

watch_history - User viewing history

categories - Content categories

🚀 Deployment
Deploy on Railway/Heroku/AWS
Build the JAR file:

bash
mvn clean package -DskipTests
Set environment variables on your hosting platform:

DATABASE_URL

DATABASE_USERNAME

DATABASE_PASSWORD

JWT_SECRET

JWT_EXPIRATION

Deploy the JAR:

bash
java -jar target/lamastudio-backend-*.jar
📊 Monitoring
Actuator endpoints available at /actuator:

/health - Application health

/metrics - Application metrics

/info - Application info

🤝 Contributing
Fork the repository

Create feature branch (git checkout -b feature/AmazingFeature)

Commit changes (git commit -m 'Add AmazingFeature')

Push to branch (git push origin feature/AmazingFeature)

Open a Pull Request

Code Style
Follow Google Java Style Guide

Write unit tests for new features

Update documentation as needed

📝 License
This project is licensed under the MIT License.

👥 Team
TinnieStudio - Development Team

📞 Contact
Email: backend@tinniestudio.com.com

Issues: GitHub Issues

🔄 API Response Format
All API responses follow a consistent format:

json
{
  "success": true,
  "data": {},
  "message": "Operation successful",
  "timestamp": "2024-01-01T12:00:00Z"
}
Error responses:

json
{
  "success": false,
  "error": "Error message",
  "status": 400,
  "timestamp": "2024-01-01T12:00:00Z"
}
🎯 Future Enhancements
Implement GraphQL API

Add WebSocket for real-time updates

Implement recommendation engine

Add support for multiple languages

Integrate payment gateway for subscriptions

Add analytics dashboard

LamaStudio Backend - Powering the Ultimate Streaming Experience

text

## **Tips for Your OpenGraph Images**

1. Create an `og-image.jpg` in:
   - Frontend: `/public/og-image.jpg`
   - Backend: `/src/main/resources/static/og-image.jpg`

2. Recommended image specifications:
   - Size: 1200×630 pixels
   - Format: JPG or PNG
   - File size: < 1MB
   - Include your logo and branding

3. For the backend API, you might also want to add:
   - Favicon: `/src/main/resources/static/favicon.ico`
   - robots.txt: For SEO control
   - sitemap.xml: For search engines

These README files provide comprehensive documentation for both projects and include the OpenGraph metadata you specified. They'll help developers understand, set up, and contribute to your LamaStudio platform!
psq

## DB MIgrateion and Backup
** Dump
docker exec -t lamastudio-db pg_dump -U postgres lamastudio_db > backup.sql

**Restore
psql -U postgres -d lamastudio_db < backup.sql

psql -U db_user -h host -d db_name

\