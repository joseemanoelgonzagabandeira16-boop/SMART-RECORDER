# SMART RECORDER

Aplicativo Android nativo para captura local de transmissões exibidas no aplicativo oficial do YouTube.

## O que já está no projeto
- Interface Android em Kotlin + Jetpack Compose.
- Acesso ao aplicativo oficial do YouTube.
- Seletor visual quadrado para posicionar e ajustar a região da webcam, com posição e tamanho salvos localmente no aparelho.
- Telas separadas para Captura, Vídeos e Ajustes.
- Fluxo de autorização MediaProjection do Android.
- Serviço em primeiro plano para gravar a tela em MP4 enquanto o usuário usa outro aplicativo.
- Processamento local com Media3 Transformer para exportar um recorte vertical 9:16, um recorte quadrado 1:1 e um terceiro MP4 com a webcam sobreposta ao vídeo vertical na posição/tamanho derivados da seleção.
- Tela de vídeos lista as capturas MP4 locais e permite abri-las no reprodutor instalado.
- Compartilhamento dos arquivos por FileProvider, sem expor caminhos internos do aplicativo.
- Arquivos de captura gravados na pasta privada de vídeos do SMART RECORDER.

## Restrições
- Sem servidor/backend.
- Sem APIs externas.
- Sem player ou plataforma intermediária.
- Processamento local no dispositivo.

## Limitações desta etapa
- A captura grava a tela inteira em um MP4 original; os três arquivos derivados são processados em sequência após parar.
- Esta primeira versão ainda não grava áudio.
- O seletor é uma referência visual 16:9, não uma prévia ao vivo do YouTube. O alinhamento com a transmissão precisa ser validado no aparelho e na orientação usados.
- A captura ainda é somente vídeo, sem áudio do sistema ou microfone.
- A exportação é sequencial e depende dos codecs de vídeo disponíveis no aparelho; falhas de exportação mantêm a gravação original.
- O Android sempre apresenta seu próprio diálogo de autorização para captura de tela; o aplicativo não pode ignorá-lo.

## Compilação
O workflow de GitHub Actions tenta gerar o APK de depuração em cada push para a branch principal.
