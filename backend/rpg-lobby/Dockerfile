FROM oven/bun:1.2.19-alpine
WORKDIR /app
COPY server.ts ./
ENV PORT=8080
EXPOSE 8080
USER bun
CMD ["bun", "run", "server.ts"]
