# SMART RECORDER

Aplicativo Android nativo para transformar uma transmissão exibida no aparelho em um vídeo vertical com webcam sobreposta.

## Captura atual
- Interface Android em Kotlin + Jetpack Compose.
- Dois seletores independentes sobre uma referência 16:9:
  - azul: região principal vertical 9:16;
  - verde: região da webcam.
- O MediaProjection entrega os quadros à memória como textura. Um pipeline OpenGL recorta a textura e envia cada região diretamente para uma superfície de codificação independente.
- Não é criado nem salvo um arquivo de vídeo da tela inteira.
- O app gera três arquivos MP4: vídeo vertical, webcam separada e composição final com webcam sobreposta.
- O vídeo principal pode incluir o áudio captado pelo microfone do aparelho, se a permissão for concedida. O áudio interno do YouTube não é capturado por esta versão.
- Os arquivos ficam inicialmente na pasta privada de vídeos do aplicativo e são copiados para Filmes/SMART RECORDER em Android 10 ou superior.
- Telas separadas para Captura, Vídeos e Ajustes.
- Compartilhamento por FileProvider.

## Restrições e permissões
- Sem servidor/backend e sem APIs externas.
- O Android sempre apresenta o diálogo do sistema para autorização de captura.
- A seleção visual usa uma referência horizontal 16:9. A posição deve ser conferida com a transmissão e a orientação do aparelho.
- O recorte principal é ajustado para a tela vertical quando a tela já está em proporção retrato.
- Dois encoders H.264 simultâneos dependem do suporte do dispositivo. Alguns aparelhos podem não disponibilizar recursos suficientes.
- A captura usa a textura integral apenas como entrada temporária em memória; os arquivos codificados contêm exclusivamente as duas regiões selecionadas.

## Compilação e testes
O workflow GitHub Actions executa `assembleDebug`, instala o APK em um emulador Android e realiza um teste básico de inicialização.
