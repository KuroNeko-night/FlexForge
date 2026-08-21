-- Docker 开发环境初始化：应用专用账号（docs/13 §3.10：应用连接不使用超级用户）。
-- 仅在数据卷首次初始化时执行；凭据为本机开发占位值，禁止用于任何共享环境。
CREATE USER flexforge WITH PASSWORD 'flexforge';
CREATE DATABASE flexforge OWNER flexforge;
GRANT ALL PRIVILEGES ON DATABASE flexforge TO flexforge;
