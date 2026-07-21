# Stage 1: Build the React Application
FROM node:22-alpine as builder
WORKDIR /app
COPY package*.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci
COPY . .
RUN npm run build

# Stage 2: Serve the application with Nginx
FROM nginx:alpine
# Copy the built files from Stage 1 into the Nginx server directory
COPY --from=builder /app/dist /usr/share/nginx/html
# Fix permissions so Nginx can read copied static files (like Cesium and favicon)
RUN chmod -R 755 /usr/share/nginx/html
# Expose port 80
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
