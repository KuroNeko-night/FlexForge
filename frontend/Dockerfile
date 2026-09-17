# 开发模式镜像（P01 骨架）：Vite dev server + 同源代理到 backend 服务。
# 生产构建与静态服务在 P06 前端动态渲染定稿后再引入 nginx（docs/09 P06）。
FROM node:26-alpine
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend ./
# Vite 启动时会在工作目录写临时配置文件，非 root 运行前需要归属 node 用户
RUN chown -R node:node /app
USER node
EXPOSE 5173
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0"]
