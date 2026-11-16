package br.com.ismtelecom.pos;

import android.content.Context;
import android.graphics.Bitmap;

import br.com.gertec.gedi.GEDI;
import br.com.gertec.gedi.enums.GEDI_PRNTR_e_Alignment;
import br.com.gertec.gedi.enums.GEDI_PRNTR_e_Status;
import br.com.gertec.gedi.exceptions.GediException;
import br.com.gertec.gedi.interfaces.IGEDI;
import br.com.gertec.gedi.interfaces.IPRNTR;
import br.com.gertec.gedi.structs.GEDI_PRNTR_st_PictureConfig;

/**
 * Impressão na bobina da GPOS780, firmware 988B, usando GEDI 2.2.6.
 *
 * Métodos e tipos conferidos no classes.jar do AAR oficial fornecido:
 * GEDI.init(Context); GEDI.getInstance(Context); IGEDI.getPRNTR();
 * IPRNTR.Init/Status/DrawPictureExt/DrawBlankLine/Output.
 *
 * Só retorna submitted=true após Output() retornar sem exceção.
 * Mesmo assim, deve-se conferir o papel impresso e o código ITF.
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

    static Result print(Context context, Bitmap receipt) {
        if (receipt == null || receipt.isRecycled()) {
            return new Result(false, "Recibo térmico indisponível.");
        }
        if (receipt.getWidth() != 384) {
            return new Result(false, "O recibo precisa ter 384 pixels de largura.");
        }
        try {
            Context application = context.getApplicationContext();
            if (application == null) application = context;

            GEDI.init(application);
            IGEDI gedi = GEDI.getInstance(application);
            if (gedi == null) {
                return new Result(false, "SDK GEDI não inicializado.");
            }

            IPRNTR printer = gedi.getPRNTR();
            if (printer == null) {
                return new Result(false, "Impressora interna indisponível no GEDI.");
            }

            printer.Init();
            GEDI_PRNTR_e_Status status = printer.Status();
            if (status != GEDI_PRNTR_e_Status.OK) {
                if (status == GEDI_PRNTR_e_Status.OUT_OF_PAPER) {
                    return new Result(false, "Sem papel ou tampa da impressora aberta.");
                }
                if (status == GEDI_PRNTR_e_Status.OVERHEAT) {
                    return new Result(false, "Impressora superaquecida. Tente após resfriar.");
                }
                if (status == GEDI_PRNTR_e_Status.PRNTR_MODULE_DISABLED) {
                    return new Result(false, "Módulo da impressora desativado no terminal.");
                }
                return new Result(false, "Status da impressora: " + String.valueOf(status));
            }

            GEDI_PRNTR_st_PictureConfig config = new GEDI_PRNTR_st_PictureConfig();
            config.alignment = GEDI_PRNTR_e_Alignment.CENTER;
            config.width = receipt.getWidth();
            config.height = receipt.getHeight();
            config.offset = 0;
            config.color = false;
            printer.DrawPictureExt(config, receipt);
            printer.DrawBlankLine(20);
            printer.Output();

            return new Result(true,
                    "Comando enviado à impressora GPOS780. Confira a bobina e leia o código.");
        } catch (GediException error) {
            return new Result(false,
                    "GEDI recusou a impressão. Verifique papel, serviço da Gertec e permissões.");
        } catch (UnsatisfiedLinkError error) {
            return new Result(false,
                    "Biblioteca nativa da Gertec indisponível para a arquitetura Android.");
        } catch (LinkageError error) {
            return new Result(false,
                    "Dependências GEDI incompatíveis com a GPOS780. Verifique o SDK 988B.");
        } catch (RuntimeException error) {
            return new Result(false,
                    "Falha ao inicializar a impressora GPOS780. Verifique o SDK e firmware.");
        }
    }
}
