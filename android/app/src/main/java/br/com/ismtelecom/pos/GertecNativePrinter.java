package br.com.ismtelecom.pos;

import android.graphics.Bitmap;

import java.lang.reflect.InvocationTargetException;

/**
 * Adaptador para o SDK GEDI do GPOS780, usando o contrato público
 * GEDI.getInstance().getPRNTR(), Init, Status, DrawPictureExt, Output.
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
    static Result print(Bitmap receipt) {
        if (receipt == null) return new Result(false, "Recibo indisponível.");
        try {
            Class<?> gedi = Class.forName("br.com.gertec.gedi.GEDI");
            Object instance = gedi.getMethod("getInstance").invoke(null);
            if (instance == null) {
                return new Result(false, "SDK GEDI não inicializado na Gertec.");
            }
            Object printer = gedi.getMethod("getPRNTR").invoke(instance);
            if (printer == null) {
                return new Result(false, "Impressora da Gertec não disponível.");
            }
            Object status = printer.getClass().getMethod("Status").invoke(printer);
            if (status == null || !"OK".equals(status.toString())) {
                return new Result(false, "Impressora: " + String.valueOf(status));
            }
            printer.getClass().getMethod("Init").invoke(printer);

            Class<?> configClass = Class.forName(
                    "br.com.gertec.gedi.structs.GEDI_PRNTR_st_PictureConfig");
            Class<?> alignClass = Class.forName(
                    "br.com.gertec.gedi.enums.GEDI_PRNTR_e_Alignment");
            Object config = configClass.getDeclaredConstructor().newInstance();
            configClass.getField("alignment").set(
                    config, Enum.valueOf((Class<? extends Enum>) alignClass, "CENTER"));
            configClass.getField("width").setInt(config, receipt.getWidth());
            configClass.getField("height").setInt(config, receipt.getHeight());
            configClass.getField("offset").setInt(config, 0);

            printer.getClass().getMethod(
                    "DrawPictureExt", configClass, Bitmap.class
            ).invoke(printer, config, receipt);
            printer.getClass().getMethod("Output").invoke(printer);
            return new Result(true,
                    "Recibo enviado à impressora térmica. Confira a bobina.");
        } catch (ClassNotFoundException | NoClassDefFoundError ex) {
            return new Result(false,
                    "SDK de impressão GEDI/PPComp GPOS780 não incluído no APK. "
                    + "É necessário o pacote SDK oficial Gertec.");
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
