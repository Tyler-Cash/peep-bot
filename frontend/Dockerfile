# syntax=docker/dockerfile:1.9

FROM --platform=$BUILDPLATFORM node:lts AS development

WORKDIR /app
COPY package.json /app/package.json
COPY package-lock.json /app/package-lock.json

RUN npm ci
COPY . /app

ENV CI=true
ENV PORT=3000

CMD [ "npm", "start" ]

FROM development AS dev-envs
RUN <<EOF
apt-get update
apt-get install -y git
EOF

RUN <<EOF
useradd -s /bin/bash -m vscode
groupadd docker
usermod -aG docker vscode
EOF
# install Docker tools (cli, buildx, compose)
COPY --from=gloursdocker/docker / /
CMD [ "npm", "start" ]

FROM development AS build

RUN ["npm", "run", "build"]

FROM nginx:1.27-alpine

COPY --from=build /app/build /usr/share/nginx/html
COPY static/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]