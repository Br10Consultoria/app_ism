package br.com.ismtelecom.pos;

import android.graphics.Bitmap;
import android.content.Context;

import java.lang.reflect.InvocationTargetException;

/**
 * Adaptador para o SDK GEDI do GPOS780, usando o contrato público
 * GEDI.init(context), GEDI.getInstance(context).getPRNTR(),
 * Init, Status, DrawPictureExt e Output (conforme exemplo Gertec GEDI).
 *
 * Sem o AAR GEDI compatível incluído no APK, retorna indisponível
 * e nunca tenta mascarar a falha abrindo o PrintManager/PDF.
 */
final class GertecNativePrinter {
    static final class Result {
        final boolean submitted;
        final String detail;

        Result(boolean submitted, String detail) {
            this.submitted = submitted;
            this.detail = detail;
        }
    }

    private GertecNativePrinter() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Result print(Context context, Bitmap receipt) {
        if (receipt == null) return new Result(false, "Recibo indisponível.");
        try {
            Class<?> gedi = Class.forName("br.com.gertec.gedi.GEDI");
            Context application = context.getApplicationContext();
            if (application == null) application = context;
            // AAR GEDI disponibilizado no exemplo Gertec chama init() primeiro.
            gedi.getMethod("init", Context.class).invoke(null, application);
            Object instance = gedi.getMethod("getInstance", Context.class)
                    .invoke(null, application);
            if (instance == null) {
                return new Result(false, "SDK GEDI não inicializado na Gertec.");
            }
            // Invocar via interfaces públicas é compatível com implementações
            // que não expõem uma classe concreta pública no APK do SDK.
            Class<?> igedi = Class.forName("br.com.gertec.gedi.interfaces.IGEDI");
            Object printer = igedi.getMethod("getPRNTR").invoke(instance);
            if (printer == null) {
                return new Result(false, "Impressora da Gertec não disponível.");
            }
            // A Gertec orienta inicializar antes de desenhar/imprimir.
            // Status() após Init() evita sondar a impressora não inicializada.
            Class<?> iprntr = Class.forName("br.com.gertec.gedi.interfaces.IPRNTR");
            iprntr.getMethod("Init").invoke(printer);
            Object status = iprntr.getMethod("Status").invoke(printer);
            if (status == null || !"OK".equals(status.toString())) {
                return new Result(false, "Impressora: " + String.valueOf(status)
                        + ". Verifique bobina, tampa e serviço Gertec.");
            }

            Class<?> configClass = Class.forName(
                    "br.com.gertec.gedi.structs.GEDI_PRNTR_st_PictureConfig");
            Class<?> alignClass = Class.forName(
                    "br.com.gertec.gedi.enums.GEDI_PRNTR_e_Alignment");
            Object config = configClass.getDeclaredConstructor().newInstance();
            configClass.getField("alignment").set(
                    config, Enum.valueOf((Class) alignClass, "CENTER"));
            configClass.getField("width").setInt(config, receipt.getWidth());
            configClass.getField("height").setInt(config, receipt.getHeight());
            configClass.getField("offset").setInt(config, 0);

            iprntr.getMethod(
                    "DrawPictureExt", configClass, Bitmap.class
            ).invoke(printer, config, receipt);
            iprntr.getMethod("Output").invoke(printer);
            return new Result(true,
                    "Recibo enviado à impressora térmica. Confira a bobina.");
        } catch (ClassNotFoundException | NoClassDefFoundError ex) {
            return new Result(false,
                    "Biblioteca GEDI compatível com GPOS780 indisponível neste APK. "
                    + "Instale o SDK oficial GPOS780 para habilitar impressão térmica.");
        } catch (InvocationTargetException ex) {
            return new Result(false,
                    "A impressora térmica recusou o comando. "
                    + "Verifique papel, SDK e versão do firmware.");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            return new Result(false,
                    "Não foi possível inicializar a impressão nativa Gertec. "
                    + "Verifique as bibliotecas GEDI/PPComp homologadas.");
        }
    }
}
