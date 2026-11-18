package br.com.ismtelecom.pos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import br.com.softwareexpress.sitef.android.CliSiTef;
import br.com.softwareexpress.sitef.android.ICliSiTefListener;

/**
 * Pagamento de fatura com cartão (crédito/débito) via CliSiTef no GPOS780.
 *
 * Módulo isolado: não altera PIX, boleto, impressão, scanner ou sessão do app.
 * Recebe pelo Intent: EXTRA_INVOICE_ID, EXTRA_VALOR (valor do IXC, ex. "123.45")
 * e EXTRA_MODALIDADE (MODALIDADE_DEBITO=2 ou MODALIDADE_CREDITO=3).
 *
 * Esta versão NÃO dá baixa da fatura no IXC: apenas executa e confirma a
 * transação no SiTef e mostra/imprime o comprovante.
 */
public class SitefPaymentActivity extends Activity implements ICliSiTefListener {

    public static final String EXTRA_INVOICE_ID = "sitef.invoiceId";
    public static final String EXTRA_VALOR = "sitef.valor";
    public static final String EXTRA_MODALIDADE = "sitef.modalidade";
    public static final int MODALIDADE_DEBITO = 2;
    public static final int MODALIDADE_CREDITO = 3;

    private static final String TAG = "ISM-SiTef";
    private static final String PREFS = "ism_sitef_config";
    private static final String K_IP = "enderecoSitef";
    private static final String K_LOJA = "codigoLoja";
    private static final String K_TERMINAL = "numeroTerminal";
    private static final String K_CNPJ_LOJA = "cnpjEstabelecimento";
    private static final String K_CNPJ_SH = "cnpjSoftwareHouse";
    private static final String K_EXTRA = "parametrosExtras";

    // Dados do estabelecimento ISM Telecom informados pela GetNet.
    private static final String DEFAULT_CNPJ_LOJA = "11716083000136";

    private static final int CAMPO_COMPROVANTE_CLIENTE = 121;
    private static final int CAMPO_COMPROVANTE_ESTAB = 122;

    private static final int NAVY = Color.rgb(16, 42, 84);
    private static final int CYAN = Color.rgb(0, 150, 199);
    private static final int GREEN = Color.rgb(25, 135, 84);
    private static final int RED = Color.rgb(200, 35, 51);
    private static final int MUTED = Color.rgb(96, 108, 125);

    /** A CliSiTef deve ser instanciada uma única vez (pinpad interno). */
    private static CliSiTef sharedCliSiTef;

    private CliSiTef clisitef;
    private SharedPreferences prefs;

    private String invoiceId;
    private String valorSitef;
    private int modalidade;
    private String cupomFiscal;
    private String dataFiscal;
    private String horaFiscal;

    private LinearLayout content;
    private TextView statusView;
    private TextView titleView;
    private String menuTitle = "";
    private String comprovanteCliente = "";
    private String comprovanteEstab = "";
    private boolean transactionRunning;
    private boolean confirmedOk;
    private boolean resultShown;

    // ------------------------------------------------------------------ ciclo

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        invoiceId = safe(getIntent().getStringExtra(EXTRA_INVOICE_ID));
        modalidade = getIntent().getIntExtra(EXTRA_MODALIDADE, MODALIDADE_CREDITO);
        valorSitef = toSitefAmount(getIntent().getStringExtra(EXTRA_VALOR));

        buildBaseScreen();

        try {
            if (sharedCliSiTef == null) {
                sharedCliSiTef = new CliSiTef(getApplicationContext());
            }
            clisitef = sharedCliSiTef;
            // Obrigatório no GPOS780: liga o pinpad virtual a esta Activity.
            clisitef.setActivity(this);
        } catch (Throwable t) {
            Log.e(TAG, "Falha ao carregar CliSiTef", t);
            showFinal(false, "Biblioteca CliSiTef indisponível neste aparelho.\n" + t.getClass().getSimpleName());
            return;
        }

        if (valorSitef == null) {
            showFinal(false, "Valor da fatura inválido. Volte e consulte novamente.");
            return;
        }
        if (!isConfigured()) {
            showConfigDialog(true);
            return;
        }
        startPayment();
    }

    @Override
    public void onBackPressed() {
        if (transactionRunning) {
            askAbort();
        } else {
            super.onBackPressed();
        }
    }

    // ------------------------------------------------------------- transação

    private void startPayment() {
        resultShown = false;
        confirmedOk = false;
        comprovanteCliente = "";
        comprovanteEstab = "";
        Date now = new Date();
        dataFiscal = new SimpleDateFormat("yyyyMMdd", Locale.US).format(now);
        horaFiscal = new SimpleDateFormat("HHmmss", Locale.US).format(now);
        // Cupom fiscal: identifica a transação; usa o nº da fatura IXC.
        cupomFiscal = invoiceId.isEmpty() ? horaFiscal : invoiceId;

        setStatus("Conectando ao SiTef...");
        int rc;
        try {
            rc = clisitef.configure(
                    prefs.getString(K_IP, ""),
                    prefs.getString(K_LOJA, ""),
                    prefs.getString(K_TERMINAL, ""),
                    additionalParams());
        } catch (Throwable t) {
            Log.e(TAG, "configure", t);
            showFinal(false, "Erro ao configurar o SiTef: " + t.getMessage());
            return;
        }
        if (rc != 0) {
            showFinal(false, "Erro na configuração do SiTef (código " + rc + ").\n"
                    + "Confira endereço, código da loja e terminal em \"Configurar SiTef\".");
            return;
        }

        try {
            transactionRunning = true;
            rc = clisitef.startTransaction(this, modalidade, valorSitef, cupomFiscal,
                    dataFiscal, horaFiscal, "", "");
        } catch (Throwable t) {
            Log.e(TAG, "startTransaction", t);
            transactionRunning = false;
            showFinal(false, "Erro ao iniciar a transação: " + t.getMessage());
            return;
        }
        if (rc != 0) {
            transactionRunning = false;
            showFinal(false, "O SiTef recusou o início da transação (código " + rc + ").");
            return;
        }
        setStatus("Siga as instruções na tela.");
    }

    private String additionalParams() {
        String extra = prefs.getString(K_EXTRA, "").trim();
        StringBuilder sb = new StringBuilder("[ParmsClient=1=")
                .append(digits(prefs.getString(K_CNPJ_LOJA, DEFAULT_CNPJ_LOJA)));
        String sh = digits(prefs.getString(K_CNPJ_SH, ""));
        if (!sh.isEmpty()) sb.append(";2=").append(sh);
        if (!extra.isEmpty()) {
            if (extra.startsWith("[")) extra = extra.substring(1);
            if (extra.endsWith("]")) extra = extra.substring(0, extra.length() - 1);
            sb.append(';').append(extra);
        }
        return sb.append(']').toString();
    }

    @Override
    public void onData(int currentStage, int command, int fieldId, int minLength, int maxLength, byte[] input) {
        final String buffer = safe(clisitef.getBuffer());
        switch (command) {
            case CliSiTef.CMD_RESULT_DATA:
                if (fieldId == CAMPO_COMPROVANTE_CLIENTE) comprovanteCliente = buffer;
                else if (fieldId == CAMPO_COMPROVANTE_ESTAB) comprovanteEstab = buffer;
                clisitef.continueTransaction("");
                break;

            case CliSiTef.CMD_SHOW_MSG_CASHIER:
            case CliSiTef.CMD_SHOW_MSG_CUSTOMER:
            case CliSiTef.CMD_SHOW_MSG_CASHIER_CUSTOMER:
                setStatus(buffer);
                clisitef.continueTransaction("");
                break;

            case CliSiTef.CMD_SHOW_MENU_TITLE:
            case CliSiTef.CMD_SHOW_HEADER:
                menuTitle = buffer;
                clisitef.continueTransaction("");
                break;

            case CliSiTef.CMD_CLEAR_MSG_CASHIER:
            case CliSiTef.CMD_CLEAR_MSG_CUSTOMER:
            case CliSiTef.CMD_CLEAR_MSG_CASHIER_CUSTOMER:
                setStatus("");
                clisitef.continueTransaction("");
                break;

            case CliSiTef.CMD_CLEAR_MENU_TITLE:
            case CliSiTef.CMD_CLEAR_HEADER:
                menuTitle = "";
                clisitef.continueTransaction("");
                break;

            case CliSiTef.CMD_CONFIRM_GO_BACK:
            case CliSiTef.CMD_CONFIRMATION:
                askConfirmation(buffer);
                break;

            case CliSiTef.CMD_PRESS_ANY_KEY:
                askPressAnyKey(buffer);
                break;

            case CliSiTef.CMD_GET_FIELD_CURRENCY:
            case CliSiTef.CMD_GET_FIELD_BARCODE:
            case CliSiTef.CMD_GET_FIELD:
                askField(buffer, command == CliSiTef.CMD_GET_FIELD_CURRENCY, minLength, maxLength);
                break;

            case CliSiTef.CMD_GET_MENU_OPTION:
                askMenu(buffer);
                break;

            case CliSiTef.CMD_ABORT_REQUEST:
            case CliSiTef.CMD_SHOW_QRCODE_FIELD:
            case CliSiTef.CMD_REMOVE_QRCODE_FIELD:
            default:
                clisitef.continueTransaction("");
                break;
        }
    }

    @Override
    public void onTransactionResult(int currentStage, int resultCode) {
        Log.i(TAG, "onTransactionResult stage=" + currentStage + " rc=" + resultCode);
        if (currentStage == 1 && resultCode == 0) {
            // Transação aprovada: confirma no SiTef.
            try {
                confirmedOk = true;
                clisitef.finishTransaction(1);
            } catch (Throwable t) {
                Log.e(TAG, "finishTransaction", t);
                confirmedOk = false;
                transactionRunning = false;
                showFinal(false, "Transação aprovada, mas houve erro ao confirmar no SiTef.\n"
                        + "Verifique pendências antes de cobrar novamente.");
            }
            return;
        }
        transactionRunning = false;
        if (currentStage == 2 && resultCode == 0 && confirmedOk) {
            showFinal(true, null);
        } else if (currentStage == 1) {
            showFinal(false, "Transação não concluída (código " + resultCode + ").\nNada foi cobrado do cliente.");
        } else {
            showFinal(false, "Falha ao confirmar a transação (código " + resultCode + ").\n"
                    + "Verifique no SiTef antes de cobrar novamente.");
        }
    }

    private void askAbort() {
        new AlertDialog.Builder(this)
                .setTitle("Cancelar pagamento?")
                .setMessage("A operação com cartão será interrompida.")
                .setPositiveButton("Cancelar pagamento", (d, w) -> {
                    try { clisitef.abortTransaction(-1); } catch (Throwable ignored) { }
                })
                .setNegativeButton("Continuar", null)
                .show();
    }

    // --------------------------------------------------------- coleta (UI)

    private void askConfirmation(String message) {
        runOnUiThread(() -> new AlertDialog.Builder(this)
                .setTitle(menuTitle.isEmpty() ? "Confirmação" : menuTitle)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Sim", (d, w) -> clisitef.continueTransaction("0"))
                .setNegativeButton("Não", (d, w) -> clisitef.continueTransaction("1"))
                .setNeutralButton("Cancelar", (d, w) -> clisitef.abortTransaction(-1))
                .show());
    }

    private void askPressAnyKey(String message) {
        runOnUiThread(() -> new AlertDialog.Builder(this)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("OK", (d, w) -> clisitef.continueTransaction(""))
                .show());
    }

    private void askMenu(String buffer) {
        // Formato: "1:Texto;2:Texto;..." — responder com o índice da opção.
        String[] raw = buffer.split(";");
        java.util.List<String> codes = new java.util.ArrayList<>();
        java.util.List<String> labels = new java.util.ArrayList<>();
        for (String item : raw) {
            if (item.trim().isEmpty()) continue;
            int sep = item.indexOf(':');
            if (sep > 0) {
                codes.add(item.substring(0, sep).trim());
                labels.add(item.substring(sep + 1).trim());
            } else {
                codes.add(String.valueOf(codes.size() + 1));
                labels.add(item.trim());
            }
        }
        final String[] codeArr = codes.toArray(new String[0]);
        final String[] labelArr = labels.toArray(new String[0]);
        runOnUiThread(() -> new AlertDialog.Builder(this)
                .setTitle(menuTitle.isEmpty() ? "Selecione" : menuTitle)
                .setCancelable(false)
                .setItems(labelArr, (d, which) -> clisitef.continueTransaction(codeArr[which]))
                .setNegativeButton("Cancelar", (d, w) -> clisitef.abortTransaction(-1))
                .show());
    }

    private void askField(String prompt, boolean currency, int minLength, int maxLength) {
        runOnUiThread(() -> {
            final EditText field = new EditText(this);
            field.setInputType(currency
                    ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                    : InputType.TYPE_CLASS_TEXT);
            if (currency) field.setHint("0,00");
            new AlertDialog.Builder(this)
                    .setTitle(prompt.isEmpty() ? (menuTitle.isEmpty() ? "Informe" : menuTitle) : prompt)
                    .setView(field)
                    .setCancelable(false)
                    .setPositiveButton("Continuar", (d, w) -> clisitef.continueTransaction(field.getText().toString().trim()))
                    .setNegativeButton("Cancelar", (d, w) -> clisitef.abortTransaction(-1))
                    .show();
        });
    }

    // ------------------------------------------------------------ telas

    private void buildBaseScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(242, 246, 250));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(18));
        scroll.addView(content);
        setContentView(scroll);

        titleView = label((modalidade == MODALIDADE_DEBITO ? "Cartão de débito" : "Cartão de crédito"), 22, NAVY, true);
        content.addView(titleView, margins(10));
        content.addView(label("Fatura IXC nº " + invoiceId, 15, NAVY, false), margins(4));
        content.addView(label("Valor: R$ " + (valorSitef == null ? "-" : valorSitef), 18, NAVY, true), margins(16));

        statusView = label("", 17, NAVY, true);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(12), dp(24), dp(12), dp(24));
        statusView.setBackground(shape(Color.WHITE, 14, Color.rgb(222, 233, 241)));
        content.addView(statusView, margins(16));

        Button cancel = button("Cancelar pagamento", RED, false);
        cancel.setOnClickListener(v -> {
            if (transactionRunning) askAbort(); else finish();
        });
        content.addView(cancel, buttonParams());
    }

    private void showFinal(boolean approved, String message) {
        if (resultShown) return;
        resultShown = true;
        runOnUiThread(() -> {
            content.removeAllViews();
            content.addView(label(approved ? "Pagamento aprovado" : "Pagamento não realizado",
                    22, approved ? GREEN : RED, true), margins(10));
            content.addView(label("Fatura IXC nº " + invoiceId + "  •  R$ " + (valorSitef == null ? "-" : valorSitef),
                    15, NAVY, false), margins(12));
            if (message != null) content.addView(label(message, 15, NAVY, false), margins(14));

            if (approved) {
                content.addView(label("Atenção: esta versão de teste não dá baixa automática da fatura no IXC.",
                        13, MUTED, false), margins(12));
                String via = !comprovanteCliente.isEmpty() ? comprovanteCliente : comprovanteEstab;
                if (!via.isEmpty()) {
                    TextView receipt = label(via, 12, Color.BLACK, false);
                    receipt.setTypeface(Typeface.MONOSPACE);
                    receipt.setPadding(dp(10), dp(10), dp(10), dp(10));
                    receipt.setBackground(shape(Color.WHITE, 8, Color.rgb(222, 233, 241)));
                    content.addView(receipt, margins(12));
                }
                if (!comprovanteCliente.isEmpty()) {
                    Button p1 = button("Imprimir via do cliente", CYAN, true);
                    p1.setOnClickListener(v -> printReceipt(comprovanteCliente));
                    content.addView(p1, buttonParams());
                }
                if (!comprovanteEstab.isEmpty()) {
                    Button p2 = button("Imprimir via do estabelecimento", CYAN, true);
                    p2.setOnClickListener(v -> printReceipt(comprovanteEstab));
                    content.addView(p2, buttonParams());
                }
            } else {
                Button retry = button("Tentar novamente", CYAN, true);
                retry.setOnClickListener(v -> {
                    content.removeAllViews();
                    buildBaseScreen();
                    if (valorSitef != null && isConfigured() && clisitef != null) startPayment();
                });
                content.addView(retry, buttonParams());
                Button cfg = button("Configurar SiTef", NAVY, false);
                cfg.setOnClickListener(v -> showConfigDialog(false));
                content.addView(cfg, buttonParams());
            }
            Button back = button("Voltar às faturas", NAVY, false);
            back.setOnClickListener(v -> finish());
            content.addView(back, buttonParams());
        });
    }

    private void setStatus(String text) {
        runOnUiThread(() -> { if (statusView != null) statusView.setText(text); });
    }

    // ------------------------------------------------------ configuração

    private boolean isConfigured() {
        return !prefs.getString(K_IP, "").trim().isEmpty()
                && !prefs.getString(K_LOJA, "").trim().isEmpty()
                && !prefs.getString(K_TERMINAL, "").trim().isEmpty();
    }

    private void showConfigDialog(boolean startAfter) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(8), dp(16), 0);
        final EditText ip = input(form, "Endereço do SiTef (IP ou host)", prefs.getString(K_IP, ""), InputType.TYPE_CLASS_TEXT);
        final EditText loja = input(form, "Código da loja (8 dígitos)", prefs.getString(K_LOJA, ""), InputType.TYPE_CLASS_TEXT);
        final EditText term = input(form, "Número do terminal (ex. AA000001)", prefs.getString(K_TERMINAL, ""), InputType.TYPE_CLASS_TEXT);
        final EditText cnpjLoja = input(form, "CNPJ do estabelecimento", prefs.getString(K_CNPJ_LOJA, DEFAULT_CNPJ_LOJA), InputType.TYPE_CLASS_NUMBER);
        final EditText cnpjSh = input(form, "CNPJ da software house (automação)", prefs.getString(K_CNPJ_SH, ""), InputType.TYPE_CLASS_NUMBER);
        final EditText extra = input(form, "Parâmetros adicionais (opcional)", prefs.getString(K_EXTRA, ""), InputType.TYPE_CLASS_TEXT);
        ScrollView sv = new ScrollView(this);
        sv.addView(form);
        new AlertDialog.Builder(this)
                .setTitle("Configurar SiTef")
                .setView(sv)
                .setCancelable(false)
                .setPositiveButton("Salvar", (d, w) -> {
                    prefs.edit()
                            .putString(K_IP, ip.getText().toString().trim())
                            .putString(K_LOJA, loja.getText().toString().trim())
                            .putString(K_TERMINAL, term.getText().toString().trim())
                            .putString(K_CNPJ_LOJA, digits(cnpjLoja.getText().toString()))
                            .putString(K_CNPJ_SH, digits(cnpjSh.getText().toString()))
                            .putString(K_EXTRA, extra.getText().toString().trim())
                            .apply();
                    if (!isConfigured()) {
                        showFinal(false, "Configuração incompleta: informe endereço, loja e terminal.");
                    } else if (startAfter) {
                        startPayment();
                    }
                })
                .setNegativeButton("Cancelar", (d, w) -> { if (startAfter) finish(); })
                .show();
    }

    private EditText input(LinearLayout parent, String labelText, String value, int type) {
        parent.addView(label(labelText, 13, MUTED, true));
        EditText e = new EditText(this);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setText(value);
        parent.addView(e, margins(8));
        return e;
    }

    // ------------------------------------------------------------ impressão

    private void printReceipt(String text) {
        new Thread(() -> {
            String msg;
            try {
                Bitmap bmp = renderReceipt(text);
                GertecNativePrinter.Result r = GertecNativePrinter.print(getApplicationContext(), bmp);
                msg = r.submitted ? "Comprovante enviado à impressora." : ("Não foi possível imprimir: " + r.detail);
            } catch (Throwable t) {
                Log.e(TAG, "print", t);
                msg = "Erro ao imprimir: " + t.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> new AlertDialog.Builder(this).setMessage(m).setPositiveButton("OK", null).show());
        }).start();
    }

    /** Converte o comprovante texto do SiTef em bitmap de 384 px (térmica 58 mm). */
    private static Bitmap renderReceipt(String text) {
        final int width = 384;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTypeface(Typeface.MONOSPACE);
        p.setTextSize(20f);
        String[] lines = text.replace("\r", "").split("\n", -1);
        // Ajusta a fonte para a linha mais longa caber na largura do papel.
        int longest = 1;
        for (String l : lines) longest = Math.max(longest, l.length());
        float maxSize = (width - 8) / (longest * 0.6f);
        if (maxSize < p.getTextSize()) p.setTextSize(Math.max(14f, maxSize));
        float lineH = p.getTextSize() * 1.25f;
        int height = (int) (lineH * lines.length + 60);
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);
        float y = lineH;
        for (String l : lines) {
            c.drawText(l, 4, y, p);
            y += lineH;
        }
        return bmp;
    }

    // ------------------------------------------------------------ utilidades

    /** "123.45" / "123,45" / "123" -> "123,45" (formato exigido pelo SiTef). */
    static String toSitefAmount(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replace("R$", "").replace(" ", "");
        if (s.isEmpty()) return null;
        if (s.contains(",") && s.contains(".")) s = s.replace(".", "").replace(",", ".");
        else s = s.replace(",", ".");
        try {
            BigDecimal v = new BigDecimal(s).setScale(2, RoundingMode.HALF_UP);
            if (v.signum() <= 0) return null;
            return v.toPlainString().replace(".", ",");
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String digits(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    private GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button button(String text, int color, boolean filled) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(15f);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(filled ? Color.WHITE : color);
        b.setBackground(shape(filled ? color : Color.WHITE, 12, filled ? 0 : color));
        return b;
    }

    private LinearLayout.LayoutParams margins(int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(bottom);
        return lp;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(53));
        lp.bottomMargin = dp(12);
        return lp;
    }

    /** Atalho usado pelo MainActivity para abrir o pagamento. */
    static void start(Context ctx, String invoiceId, String valor, int modalidade) {
        Intent i = new Intent(ctx, SitefPaymentActivity.class);
        i.putExtra(EXTRA_INVOICE_ID, invoiceId);
        i.putExtra(EXTRA_VALOR, valor);
        i.putExtra(EXTRA_MODALIDADE, modalidade);
        ctx.startActivity(i);
    }
}
