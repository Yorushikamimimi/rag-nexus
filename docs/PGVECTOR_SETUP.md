# pgvector 扩展说明

本应用依赖 PostgreSQL 的 **pgvector** 扩展（向量存储）。若启动报错：

```text
错误: extension "vector" is not available
Could not open extension control file ".../vector.control": No such file or directory
```

说明当前连接的 PostgreSQL **未安装 pgvector**，请按下面两种方式之一处理。

---

## 方案 A：用 Docker 跑带 pgvector 的 PostgreSQL（推荐）

无需改本机已安装的 PostgreSQL，直接起一个带 pgvector 的库：

```powershell
docker run -d --name pgvector `
  -p 5433:5432 `
  -e POSTGRES_USER=postgres `
  -e POSTGRES_PASSWORD=N-buna#Ghost2020 `
  -e POSTGRES_DB=postgres `
  pgvector/pgvector
```

然后在本项目中把数据源端口改为 **5433**（避免和本机 5432 冲突），例如在 `application-local.yml` 中：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5433/postgres
    username: postgres
    password: ${SPRING_DATASOURCE_PASSWORD:N-buna#Ghost2020}
```

或在启动前设置环境变量：

```powershell
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5433/postgres"
$env:SPRING_DATASOURCE_PASSWORD = "N-buna#Ghost2020"
mvn spring-boot:run
```

---

## 方案 B：在本机 PostgreSQL（D:\PostgreSQL）上安装 pgvector

1. **安装依赖**：Visual Studio（含 C++ 构建工具）、Git。
2. **编译安装 pgvector**（在「适用于 VS 的 x64 本机工具命令提示」或已配置好 `cl`/`nmake` 的终端中）：

   ```cmd
   set PGROOT=D:\PostgreSQL\16
   git clone --branch v0.8.0 https://github.com/pgvector/pgvector.git
   cd pgvector
   nmake /F Makefile.win
   nmake /F Makefile.win install
   ```

   将 `D:\PostgreSQL\16` 换成你的实际安装路径（可能为 `D:\PostgreSQL\15` 等）。

3. **重启 PostgreSQL 服务**，在对应数据库中执行：

   ```sql
   CREATE EXTENSION IF NOT EXISTS vector;
   ```

若本机是绿色版或路径不同，需把编译出的 `vector.control`、`vector--*.sql` 放到 `D:\PostgreSQL\share\extension\`，`.dll` 放到 `D:\PostgreSQL\lib\`。详细可参考：<https://github.com/pgvector/pgvector#installation>。

---

总结：**必须先让当前连接到的 PostgreSQL 具备 vector 扩展**（Docker 用 `pgvector/pgvector` 镜像，或在本机安装 pgvector），应用才能正常启动。
