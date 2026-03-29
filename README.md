# 📦 deployment-maven

![Maven](https://img.shields.io/badge/Maven-Plugin-blue)
![Java](https://img.shields.io/badge/Java-8%2B-orange)
![Kubernetes](https://img.shields.io/badge/Kubernetes-Supported-brightgreen)
![Docker](https://img.shields.io/badge/Docker-Enabled-blue)
![License](https://img.shields.io/badge/license-Internal-lightgrey)

A Maven plugin to **deploy Spring / Spring Boot applications** into modern infrastructure:

* ☸️ Kubernetes (Helm + Docker + Nexus)
* 🐱 Apache Tomcat (Manager API)

---

# 🚀 Key Features

* ⚡ **One command deployment**

  ```bash
  mvn push:k8s
  mvn push:tomcat
  ```

* 🔁 Reusable across projects

* 🧩 Configurable via `pom.xml`

* 🧪 CLI override support

* 🐳 Built-in Docker + Nexus integration

* ☸️ Helm-based Kubernetes deployment

* 💻 No shell scripts required (pure Java)

---

# 🏗️ Architecture Overview

```text
                ┌──────────────────────┐
                │   Maven Project      │
                │ (Spring / Boot App)  │
                └─────────┬────────────┘
                          │
                          │ mvn push:k8s / push:tomcat
                          ▼
                ┌──────────────────────┐
                │ deployment-maven     │
                │ (Maven Plugin)       │
                └─────────┬────────────┘
                          │
          ┌───────────────┼────────────────┐
          │                                │
          ▼                                ▼
┌──────────────────────┐        ┌──────────────────────┐
│   Kubernetes Flow    │        │    Tomcat Flow       │
├──────────────────────┤        ├──────────────────────┤
│ Build Docker Image   │        │ Build WAR            │
│ Push to Nexus        │        │ Deploy via Manager   │
│ Helm Deploy          │        │ Update Application   │
│ Apply Ingress        │        │                      │
└──────────────────────┘        └──────────────────────┘
```

---

# 🧱 Project Structure

```bash
deployment-maven/
├── pom.xml
└── src/main/java/com/tark/deployment/
    ├── core/
    │   ├── Deploy.java
    │   ├── DeployTomcat.java
    │   ├── DeployLibs.java
    │   ├── ExecHttp.java
    │   └── UI.java
    │
    └── mojo/
        ├── K8sMojo.java
        └── TomcatMojo.java
```

---

# ⚙️ Installation

Install the plugin locally:

```bash
cd deployment-maven
mvn clean install
```

---

# 🧩 Usage

Add plugin into your project:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>com.tark</groupId>
      <artifactId>deployment-maven</artifactId>
      <version>1.0.0</version>

      <configuration>
        <projectId>APP-NAME</projectId>
      </configuration>

    </plugin>
  </plugins>
</build>
```

---

# ▶️ Commands

## ☸️ Kubernetes Deployment

```bash
mvn push:k8s
```

---

## 🐱 Tomcat Deployment

```bash
mvn push:tomcat
```

---

# ⚡ CLI Override

```bash
mvn push:k8s -DprojectId=MYAPP
```

---

# 🔄 Deployment Flow

## Kubernetes

```text
pom.xml
   ↓
Extract metadata
   ↓
Sync dependencies → Nexus
   ↓
Build Docker image
   ↓
Push to Nexus registry
   ↓
Helm deploy → Kubernetes
   ↓
Ingress + environment setup
```

---

## Tomcat

```text
Build WAR
   ↓
Select server
   ↓
Upload via Manager API
   ↓
Application updated
```

---

# ⚙️ Configuration

## Minimal

```xml
<configuration>
  <projectId>BIMA</projectId>
</configuration>
```

---

## Advanced

```xml
<configuration>
  <projectId>BIMA</projectId>
  <replicas>2</replicas>
  <dockerTag>v1</dockerTag>
</configuration>
```

---

# 🔧 Requirements

* Java 8+
* Maven
* Docker
* kubectl
* Helm (auto-installed if missing)
* Nexus Repository access

---

# ⚠️ Important Notes

* This plugin will:

  * Modify Docker config (`insecure-registries`)
  * Authenticate to Nexus
  * Push Docker images
* Ensure proper permissions (sudo/admin access may be required)

---

# 🧪 Example Usage

```bash
# full pipeline
mvn clean install push:k8s

# deploy only
mvn push:k8s

# override config
mvn push:k8s -DprojectId=MYAPP

# tomcat deployment
mvn push:tomcat
```

---

# 🏁 Recommended Workflow

```bash
mvn clean package
mvn deploy          # publish artifact
mvn push:k8s        # deploy application
```

---

# 🔮 Roadmap

* [ ] Non-interactive mode (CI/CD ready)
* [ ] Multi-environment support (dev/stg/prod)
* [ ] Config from `.env` / `application.yml`
* [ ] Rollback support (`push:rollback`)
* [ ] GitLab CI / Jenkins integration
* [ ] Logging improvement (SLF4J)

---

# 👨‍💻 Author

**Exel Tarkus**

---

# 💥 Summary

| Command           | Description          |
| ----------------- | -------------------- |
| `mvn push:k8s`    | Deploy to Kubernetes |
| `mvn push:tomcat` | Deploy to Tomcat     |
