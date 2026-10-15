# The targetservers router (nginx.conf): /targets/<name>/ (or /<set>/<name>/) to the target container <name>, port 8080.
FROM nginx:1.28.3-alpine
LABEL org.opencontainers.image.title="Touchstone target router" \
      org.opencontainers.image.description="Routes /<set>/<name>/ to the LWS target server container <name>" \
      org.opencontainers.image.source="https://github.com/ebremer/touchstone"
COPY nginx.conf /etc/nginx/nginx.conf
# nginx.conf keeps its pid and temporary files in /tmp, so it runs as the image's unprivileged user.
USER nginx
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
ENTRYPOINT ["nginx", "-g", "daemon off;"]
