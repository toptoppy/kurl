# Deployment template

Copy `docker-compose.yml` and the environment template to the target host. Do not commit production secrets.

```sh
mkdir -p data
sudo chown 65532:65532 data
printf 'KURL_API_KEY=replace-with-a-long-random-value\n' > .env
chmod 600 .env
docker compose up -d
docker compose ps
```

The host must already have the external `toptoppy-website_application` Docker network. Configure the public Caddy gateway separately:

```caddy
s.kananek.top {
    reverse_proxy kurl:8080
}
```

The deployment task is not complete until the image is published, the host deployment is applied, the container reports healthy, and the public HTTPS health endpoint is verified.
