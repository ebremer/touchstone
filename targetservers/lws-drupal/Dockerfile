# syntax=docker/dockerfile:1
# lws-drupal (github.com/ebremer/lws-drupal, the Drupal 11 LWS modules) as a target server: a Drupal site with the
# modules lws, lws_authz, lws_storage, lws_index and lws_notify, one storage at /lws/<slug>/, and the site's own
# authorization server; PHP-FPM behind nginx in this container, the database in its own (compose service
# lws-drupal-db, MariaDB). See ../README.md for the contract and entrypoint.sh for the settings.
#
# The source is the repository at LWS_DRUPAL_REF. To build a local checkout instead:
#   docker build --build-context source=/path/to/lws-drupal targetservers/lws-drupal

ARG LWS_DRUPAL_REPO=https://github.com/ebremer/lws-drupal.git
ARG LWS_DRUPAL_REF=4ee4a5cfb1c19a700d32e3c95303aa8d2de9557f

# ---- source ----
FROM alpine/git:2.52.0 AS clone
ARG LWS_DRUPAL_REPO
ARG LWS_DRUPAL_REF
RUN git init -q /src && git -C /src remote add origin "$LWS_DRUPAL_REPO" \
 && git -C /src fetch -q --depth 1 origin "$LWS_DRUPAL_REF" && git -C /src checkout -q FETCH_HEAD && rm -rf /src/.git

FROM scratch AS source
COPY --from=clone /src/ /

# ---- site ----
# The official image's /opt/drupal is drupal/recommended-project with Drupal 11.4.9; the module joins it through a
# path repository (copied, not linked), with ebremer/lws-client at LWS_CLIENT_REF and Drush.
FROM drupal:11.4.9-php8.4-fpm-trixie
ARG LWS_DRUPAL_REF
ARG LWS_CLIENT_REF=ff51462745c468afe054726e6705c8af218531e1
LABEL org.opencontainers.image.title="lws-drupal (LWS target server)" \
      org.opencontainers.image.source="https://github.com/ebremer/lws-drupal" \
      org.opencontainers.image.revision="${LWS_DRUPAL_REF}"
RUN apt-get update && apt-get install -y --no-install-recommends git nginx unzip \
 && rm -rf /var/lib/apt/lists/* \
 && rm -f /etc/nginx/sites-enabled/default
COPY --from=source / /opt/lws-drupal/
WORKDIR /opt/drupal
ENV COMPOSER_ALLOW_SUPERUSER=1 COMPOSER_MEMORY_LIMIT=-1
RUN --mount=type=cache,target=/root/.cache/composer \
    composer config repositories.lws '{"type": "path", "url": "/opt/lws-drupal", "options": {"symlink": false}}' \
 && composer config repositories.lws-client vcs https://github.com/ebremer/lws-client \
 && composer require --no-interaction --no-progress \
      "ebremer/lws-client:dev-main#${LWS_CLIENT_REF}" 'ebremer/lws-drupal:*@dev' 'drush/drush:^13' \
 && ln -s /opt/drupal/vendor/bin/drush /usr/local/bin/drush
COPY nginx.conf /etc/nginx/nginx.conf
COPY php.ini /usr/local/etc/php/conf.d/zz-lws.ini
COPY php-fpm.conf /usr/local/etc/php-fpm.d/zz-lws.conf
COPY --chmod=755 entrypoint.sh /usr/local/bin/entrypoint.sh
COPY write-settings.php /usr/local/lib/lws-drupal/write-settings.php
# /data keeps what the database does not: the private files (content, and lws_authz/keys, the authorization
# server's signing keys), the public files, and the site's hash salt.
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
