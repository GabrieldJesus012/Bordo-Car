# Bordo Car

Painel do carro para o Android Auto: música, próxima curva do Waze / Google Maps, velocidade e limite da via, bússola, km do dia e da semana, hora, bateria do celular e clima. A navegação fica com o Waze ou o Google Maps.

**Baixar:** https://github.com/GabrieldJesus012/Bordo-Car/releases/download/app/BordoCar.apk

## Como o app é gerado
O GitHub Actions compila a pasta `BordoCar/` a cada envio para `main`, assina com a mesma chave do Horizon Nav BR (o arquivo da chave é pego do repositório Horizon-Maps na hora da compilação, não fica guardado aqui) e publica o APK na release **app** (o link acima).

Precisa de um secret neste repositório: **Settings › Secrets and variables › Actions › New repository secret**
- Name: `KEYSTORE_PASSWORD`
- Secret: a mesma senha da chave do Horizon Nav BR.

Sem o secret, o app é compilado (para conferir), mas não é publicado.

## No Android Auto
Apps instalados fora da Play Store só aparecem com **Fontes desconhecidas** ligado nas configurações de desenvolvedor do Android Auto (toque 10× em "Versão" para ativá-las).
