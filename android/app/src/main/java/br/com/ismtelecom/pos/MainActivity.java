package br.com.ismtelecom.pos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.pdf.PdfDocument;
import android.graphics.Paint;
import android.content.ActivityNotFoundException;
import android.graphics.RectF;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Telas de atendimento ISM para terminais POS; sem integração proprietária de impressora. */
public class MainActivity extends Activity {
    private static final int NAVY = Color.rgb(8, 58, 112);
    private static final int CYAN = Color.rgb(29, 162, 215);
    private static final int PALE = Color.rgb(242, 248, 252);
    private static final int MUTED = Color.rgb(103, 126, 143);
    private static final int GREEN = Color.rgb(58, 169, 109);
    private static final int RED = Color.rgb(202, 66, 103);
    private static final long SESSION_MILLIS = 180_000L; // Mesmo TTL do backend.
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView sessionFooter;
    private String session = "";
    private JSONObject invoiceResponse;
    private String screenType = "home";
    private int filterMode = 0; // 0 = todas, 1 = em aberto, 2 = vencidas.
    private long deadline = 0;
    private int requestEpoch = 0;
    private boolean loading = false;
    private File tempPdf;
    private PdfRenderer renderer;
    private ParcelFileDescriptor pdfDescriptor;
    private int currentPage = 0;

    private final Runnable sessionTick = new Runnable() {
        @Override public void run() {
            if (deadline == 0) return;
            long left = Math.max(0, (deadline - SystemClock.elapsedRealtime() + 999) / 1000);
            if (sessionFooter != null) sessionFooter.setText("Fim da sessão: " + left + " segundos");
            if (left == 0) {
                showHome();
                dialog("Sessão encerrada por segurança. Inicie outra consulta.");
            } else {
                ui.postDelayed(this, 1000);
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        showHome();
    }

    private int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v + .5f);
    }

    private GradientDrawable shape(int color, int corner, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(corner));
        if (strokeColor != 0) g.setStroke(dp(1), strokeColor);
        return g;
    }

    private LinearLayout col() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView label(String value, int size, int color, boolean strong) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (strong) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams margins(int width, int height, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height);
        p.bottomMargin = dp(bottom);
        return p;
    }

    private Button button(String text, int color, boolean filled) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(9), dp(8), dp(9), dp(8));
        b.setTextColor(filled ? Color.WHITE : color);
        b.setBackground(shape(filled ? color : Color.WHITE, 12, filled ? 0 : color));
        return b;
    }

    private void primary(String text, View.OnClickListener click) {
        Button b = button(text, CYAN, true);
        b.setOnClickListener(click);
        content.addView(b, margins(-1, dp(53), 12));
    }

    /** Marca ISM desenhada em vetor: o antigo ism_logo.png do APK está truncado. */
    private View logo(int height) {
        return new View(this) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                float width = getWidth(), fullHeight = getHeight();
                float scale = Math.min((width - dp(8)) / 250f,
                                       (fullHeight - dp(4)) / 105f);
                if (scale <= 0f) return;
                canvas.save();
                canvas.translate((width - 250f * scale) / 2f,
                                 (fullHeight - 105f * scale) / 2f);
                canvas.scale(scale, scale);

                // Velocímetro em azul, como na marca ISM Telecom.
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeWidth(8.2f);
                paint.setColor(Color.rgb(24, 128, 180));
                canvas.drawArc(new RectF(8, 9, 114, 116), 196f, 135f, false, paint);

                paint.setStrokeWidth(4f);
                paint.setColor(Color.rgb(53, 178, 202));
                canvas.drawArc(new RectF(21, 22, 101, 102), 195f, 128f, false, paint);

                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.rgb(9, 79, 131));
                for (int i = 0; i < 7; i++) {
                    float angle = (float) Math.toRadians(209f + i * 15.5f);
                    float radius = 46f;
                    float cx = 61f + (float) Math.cos(angle) * radius;
                    float cy = 63f + (float) Math.sin(angle) * radius;
                    canvas.drawCircle(cx, cy, 2.2f, paint);
                }

                paint.setColor(Color.rgb(194, 52, 74));
                paint.setStrokeWidth(2.9f);
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawLine(68f, 57f, 99f, 27f, paint);

                paint.setStyle(Paint.Style.FILL);
                paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                paint.setTextSize(46f);
                paint.setColor(Color.rgb(47, 146, 175));
                canvas.drawText("ISM", 79f, 66f, paint);

                paint.setTextSize(24f);
                paint.setColor(Color.rgb(7, 66, 119));
                canvas.drawText("Telecom", 91f, 90f, paint);

                paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                paint.setTextSize(10.5f);
                paint.setColor(Color.rgb(60, 156, 174));
                canvas.drawText("100% Fibra Óptica", 91f, 102f, paint);
                canvas.restore();
            }
        };
    }

    private void initScreen(String title, String subtitle, boolean back) {
        LinearLayout root = col();
        root.setBackgroundColor(PALE);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = col();
        content.setPadding(dp(18), dp(12), dp(18), dp(16));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        if (back) {
            TextView goBack = label("← Voltar", 13, NAVY, true);
            goBack.setGravity(Gravity.CENTER);
            goBack.setContentDescription("Voltar para a tela anterior");
            goBack.setOnClickListener(v -> backToInvoicesOrHome());
            header.addView(goBack, new LinearLayout.LayoutParams(dp(77), dp(58)));
        } else {
            header.addView(new View(this), new LinearLayout.LayoutParams(dp(77), dp(58)));
        }
        View brand = logo(back ? 58 : 102);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(0, dp(back ? 58 : 102), 1);
        header.addView(brand, logoParams);
        TextView exit = label("Sair ×", 13, NAVY, true);
        exit.setGravity(Gravity.CENTER);
        exit.setContentDescription("Sair do aplicativo ISM Telecom");
        exit.setOnClickListener(v -> confirmExit());
        header.addView(exit, new LinearLayout.LayoutParams(dp(77), dp(58)));
        content.addView(header, margins(-1, -2, back ? 10 : 34));

        if (!title.isEmpty()) {
            TextView heading = label(title, 23, NAVY, true);
            content.addView(heading, margins(-1, -2, 6));
        }
        if (!subtitle.isEmpty()) {
            TextView sub = label(subtitle, 14, MUTED, false);
            content.addView(sub, margins(-1, -2, 18));
        }

        sessionFooter = null;
        if (deadline > 0) {
            sessionFooter = label("", 11, MUTED, false);
            sessionFooter.setGravity(Gravity.RIGHT);
            sessionFooter.setPadding(dp(16), dp(9), dp(16), dp(9));
            sessionFooter.setBackgroundColor(Color.WHITE);
            root.addView(sessionFooter, new LinearLayout.LayoutParams(-1, dp(34)));
            long left = Math.max(0, (deadline - SystemClock.elapsedRealtime() + 999) / 1000);
            sessionFooter.setText("Fim da sessão: " + left + " segundos");
        }
        setContentView(root);
    }

    private void backToInvoicesOrHome() {
        switch (screenType) {
            case "payment":
                if (invoiceResponse != null && isSessionValid()) renderInvoices();
                else showHome();
                break;
            case "invoices":
                showLookup();
                break;
            case "home":
                confirmExit();
                break;
            default:
                showHome();
                break;
        }
    }

    private void confirmExit() {
        new AlertDialog.Builder(this)
                .setTitle("Sair do ISM Telecom?")
                .setMessage("A consulta atual será encerrada. Deseja fechar o aplicativo?")
                .setNegativeButton("Cancelar", (d, w) -> {})
                .setPositiveButton("Sair", (d, w) -> {
                    resetSession();
                    closePdf();
                    finishAffinity();
                })
                .show();
    }

    private String terminalKey() {
        String compiled = BuildConfig.TERMINAL_KEY;
        return compiled != null && compiled.length() >= 32
                ? compiled : TerminalKeyStore.read(this);
    }

    private String apiBase() {
        return BuildConfig.API_BASE.startsWith("https://")
                ? BuildConfig.API_BASE : "https://app.ismtelecom.com.br";
    }

    /** Pareamento de homologação: chave local, não é credencial do IXC. */
    private void showTerminalSetup() {
        resetSession();
        closePdf();
        screenType = "setup";
        initScreen("Configurar terminal",
                "Aponte a maquininha para a API ISM. Informe a chave TERMINAL_KEY do servidor, nunca o token do IXC.", true);
        content.addView(label("Servidor: " + apiBase(), 13, NAVY, true),
                margins(-1, -2, 18));
        EditText credential = field("Chave do terminal", 
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        credential.setSelectAllOnFocus(true);
        content.addView(label("A chave deve ter no mínimo 32 caracteres. "
                        + "Ela ficará cifrada no armazenamento privado do Android.",
                12, MUTED, false), margins(-1, -2, 14));

        primary("Salvar configuração", v -> {
            String key = credential.getText().toString().trim();
            if (key.length() < 32 || key.length() > 512) {
                dialog("A chave deve ter entre 32 e 512 caracteres."); return;
            }
            try {
                TerminalKeyStore.save(this, key);
                credential.setText("");
                showHome();
                dialog("Terminal configurado. Faça uma consulta para validar a autenticação.");
            } catch (Exception ex) {
                dialog("Não foi possível salvar a configuração neste Android.");
            }
        });

        Button forget = button("Apagar chave salva", RED, false);
        forget.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Desvincular terminal?")
                .setMessage("A maquininha precisará de uma chave válida para novas consultas.")
                .setNegativeButton("Cancelar", (d, w) -> {})
                .setPositiveButton("Desvincular", (d, w) -> {
                    TerminalKeyStore.clear(this);
                    showHome();
                    dialog("Chave local apagada.");
                }).show());
        content.addView(forget, margins(-1, dp(48), 12));
    }

    private EditText field(String hint, int inputType) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setTextSize(16);
        edit.setHint(hint);
        edit.setInputType(inputType);
        edit.setPadding(dp(14), dp(10), dp(14), dp(10));
        edit.setBackground(shape(Color.WHITE, 10, Color.rgb(208, 225, 235)));
        content.addView(edit, margins(-1, dp(52), 14));
        return edit;
    }

    private String digits(String s) { return s.replaceAll("[^0-9]", ""); }

    private String money(String raw) {
        try {
            return "R$ " + String.format(new Locale("pt", "BR"), "%,.2f", new BigDecimal(raw));
        } catch (Exception ex) {
            return "R$ " + raw;
        }
    }

    private String dateBR(String date) {
        try {
            return LocalDate.parse(date).format(DateTimeFormatter.ofPattern("dd/MM/yyyy", new Locale("pt", "BR")));
        } catch (Exception ex) {
            return date;
        }
    }

    private boolean isLate(String date) {
        try { return LocalDate.parse(date).isBefore(LocalDate.now()); }
        catch (Exception ex) { return false; }
    }

    private boolean isSessionValid() {
        return !session.isEmpty() && deadline > SystemClock.elapsedRealtime();
    }

    private void startSession() {
        ui.removeCallbacks(sessionTick);
        deadline = SystemClock.elapsedRealtime() + SESSION_MILLIS;
        ui.post(sessionTick);
    }

    private void resetSession() {
        ui.removeCallbacks(sessionTick);
        deadline = 0;
        session = "";
        invoiceResponse = null;
        filterMode = 0;
        requestEpoch++;
    }

    private void showHome() {
        screenType = "home";
        resetSession();
        closePdf();
        initScreen("", "", false);
        TextView greeting = label("Como podemos ajudar?", 21, NAVY, true);
        greeting.setGravity(Gravity.CENTER);
        content.addView(greeting, margins(-1, -2, 22));

        Button contract = button("Quero contratar", NAVY, false);
        contract.setOnClickListener(v -> showLead());
        content.addView(contract, margins(-1, dp(86), 14));

        Button bills = button("Boletos e pagamentos", CYAN, false);
        bills.setOnClickListener(v -> showLookup());
        content.addView(bills, margins(-1, dp(86), 22));

        TextView warning = label("Serviço de consulta. Confirme os dados antes de pagar no seu banco.", 12, MUTED, false);
        warning.setGravity(Gravity.CENTER);
        content.addView(warning, margins(-1, -2, 20));

        Button config = button("Configurar terminal", NAVY, false);
        config.setTextSize(13);
        config.setOnClickListener(v -> showTerminalSetup());
        content.addView(config, margins(-1, dp(46), 10));

        TextView status = label(
                terminalKey().length() >= 32 ? "Chave de terminal salva" : "Terminal não configurado",
                11, MUTED, false);
        status.setGravity(Gravity.CENTER);
        content.addView(status, margins(-1, -2, 8));
    }

    private void showLookup() {
        screenType = "lookup";
        resetSession();
        closePdf();
        initScreen("Boletos e\npagamentos", "Insira seu CPF ou CNPJ para consultar as faturas.", true);
        EditText document = field("CPF ou CNPJ", InputType.TYPE_CLASS_NUMBER);
        primary("Avançar", v -> {
            String number = digits(document.getText().toString());
            if (number.length() != 11 && number.length() != 14) {
                dialog("Informe um CPF de 11 dígitos ou CNPJ de 14 dígitos."); return;
            }
            hideKeyboard(document);
            JSONObject body = new JSONObject();
            try { body.put("cpf", number); } catch (JSONException ignored) {}
            call("/api/v1/faturas", body, result -> {
                invoiceResponse = result;
                session = result.optString("session");
                if (!session.isEmpty()) startSession();
                renderInvoices();
            });
        });
    }

    private void renderInvoices() {
        screenType = "invoices";
        closePdf();
        if (invoiceResponse == null) { showHome(); return; }
        JSONArray invoices = invoiceResponse.optJSONArray("faturas");
        int count = invoices == null ? 0 : invoices.length();
        initScreen("Boletos e\npagamentos",
                "Olá! Encontramos " + count + (count == 1 ? " fatura disponível." : " faturas disponíveis."), true);

        if (count == 0) {
            TextView empty = label(invoiceResponse.optString("mensagem", "Não há cobranças disponíveis."),
                    16, NAVY, true);
            content.addView(empty, margins(-1, -2, 16));
            primary("Nova consulta", v -> showLookup());
            return;
        }

        Button filter = button("Filtrar faturas  ▾", CYAN, true);
        filter.setTextSize(13);
        filter.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Exibir faturas")
                .setItems(new String[]{"Todas", "Em aberto", "Vencidas"},
                        (d, selected) -> { filterMode = selected; renderInvoices(); })
                .show());
        LinearLayout filterContainer = new LinearLayout(this);
        filterContainer.setGravity(Gravity.RIGHT);
        filterContainer.addView(filter, new LinearLayout.LayoutParams(dp(160), dp(43)));
        content.addView(filterContainer, margins(-1, -2, 12));

        int visible = 0;
        for (int i = 0; i < count; i++) {
            JSONObject invoice = invoices.optJSONObject(i);
            if (invoice == null) continue;
            String due = invoice.optString("vencimento");
            boolean late = isLate(due);
            if ((filterMode == 1 && late) || (filterMode == 2 && !late)) continue;
            addInvoiceCard(invoice, late);
            visible++;
        }
        if (visible == 0) {
            TextView empty = label("Nenhuma fatura corresponde ao filtro selecionado.", 14, MUTED, false);
            content.addView(empty);
        }
    }

    private void addInvoiceCard(JSONObject invoice, boolean late) {
        final String id = invoice.optString("id");
        LinearLayout card = col();
        card.setBackground(shape(Color.WHITE, 16, Color.rgb(222, 233, 241)));
        card.setPadding(dp(0), 0, 0, dp(11));
        int accent = late ? RED : GREEN;
        TextView status = label(late ? "Vencido" : "Em aberto", 14, Color.WHITE, true);
        status.setGravity(Gravity.CENTER);
        status.setBackground(shape(accent, 14, 0));
        card.addView(status, new LinearLayout.LayoutParams(-1, dp(30)));

        TextView due = label("Vencimento: " + dateBR(invoice.optString("vencimento")), 13, NAVY, false);
        due.setPadding(dp(12), dp(12), dp(12), 0);
        card.addView(due);
        TextView price = label("Valor: " + money(invoice.optString("valor")), 15, NAVY, true);
        price.setPadding(dp(12), dp(4), dp(12), dp(10));
        card.addView(price);

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(dp(8), 0, dp(8), 0);
        addCardAction(actions, "Imprimir\nboleto", () -> getBoleto(id, "imprimir"));
        addCardAction(actions, "Enviar\nboleto", () -> getBoleto(id, "enviar"));
        addCardAction(actions, "Pagar\nboleto", () -> showPaymentOptions(id));
        card.addView(actions);
        content.addView(card, margins(-1, -2, 14));
    }

    private void addCardAction(LinearLayout actions, String title, Runnable click) {
        Button b = button(title, NAVY, false);
        b.setTextSize(11);
        b.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        b.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(53), 1);
        p.leftMargin = dp(3);
        p.rightMargin = dp(3);
        actions.addView(b, p);
    }

    private void showPaymentOptions(String id) {
        if (!isSessionValid()) { showHome(); dialog("Sessão expirada. Faça nova consulta."); return; }
        new AlertDialog.Builder(this).setTitle("Como deseja pagar?")
                .setItems(new String[]{"PIX (QR Code)", "Código de barras", "Visualizar boleto PDF"},
                        (d, item) -> requestPayment(id, new String[]{"pix", "barra", "boleto"}[item]))
                .show();
    }

    private void getBoleto(String id, String operation) {
        if (!isSessionValid()) { showHome(); dialog("Sessão expirada. Faça nova consulta."); return; }
        JSONObject request = new JSONObject();
        try { request.put("session", session); } catch (JSONException ignored) {}
        call("/api/v1/faturas/" + id + "/boleto", request, result -> {
            String encoded = result.optString("pdf_base64");
            if (encoded.isEmpty()) {
                dialog("Esta cobrança não disponibilizou o PDF para " + operation + ".");
                return;
            }
            try {
                savePdf(encoded);
                if ("enviar".equals(operation)) sharePdf();
                else printPdf();
            } catch (Exception err) {
                dialog("Não foi possível preparar o boleto PDF.");
            }
        });
    }

    private void requestPayment(String id, String kind) {
        if (!isSessionValid()) { showHome(); dialog("Sessão expirada. Faça nova consulta."); return; }
        JSONObject request = new JSONObject();
        try { request.put("session", session); } catch (JSONException ignored) {}
        call("/api/v1/faturas/" + id + "/" + kind, request, this::showPayment);
    }

    private void showPayment(JSONObject result) {
        screenType = "payment";
        closePdf();
        String kind = result.optString("tipo");
        initScreen("Dados para pagamento",
                money(result.optString("valor")) + "  •  Vencimento: " + dateBR(result.optString("vencimento")), true);
        String code = result.optString("codigo");
        if ("pix".equals(kind)) {
            content.addView(label("PIX copia e cola", 18, NAVY, true), margins(-1, -2, 12));
            displayQr(code);
            displayCode(code);
            TextView help = label("Após o cliente pagar no banco, toque para consultar a baixa no IXC. " +
                    "A exibição do QR Code não confirma o recebimento.", 12, MUTED, false);
            content.addView(help, margins(-1, -2, 12));
            String invoiceId = result.optString("id");
            primary("Verificar pagamento PIX", v -> checkPixPayment(invoiceId));
        } else if ("barra".equals(kind)) {
            content.addView(label("Linha digitável", 18, NAVY, true), margins(-1, -2, 12));
            displayCode(code);
        } else if ("boleto".equals(kind)) {
            String encoded = result.optString("pdf_base64");
            String link = result.optString("url");
            if (!encoded.isEmpty()) {
                try {
                    displayPdf(encoded);
                    primary("Enviar boleto", v -> sharePdf());
                    primary("Imprimir boleto", v -> printPdf());
                } catch (Exception ex) { dialog("Não foi possível abrir o PDF recebido."); }
            } else if (link.startsWith("https://")) {
                primary("Abrir boleto", v -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link))); }
                    catch (Exception ex) { dialog("Nenhum visualizador compatível instalado."); }
                });
            } else {
                dialog("O IXC não disponibilizou este boleto.");
            }
        }
        TextView reminder = label(
                "Confira beneficiário, valor e vencimento no aplicativo do seu banco antes de pagar.",
                12, MUTED, false);
        content.addView(reminder, margins(-1, -2, 15));
    }

    private void checkPixPayment(String invoiceId) {
        if (!isSessionValid()) {
            showHome();
            dialog("Sessão expirada. Consulte o cliente novamente.");
            return;
        }
        if (!invoiceId.matches("[0-9]+")) {
            dialog("Cobrança inválida para confirmação.");
            return;
        }
        JSONObject request = new JSONObject();
        try { request.put("session", session); } catch (JSONException ignored) {}
        call("/api/v1/faturas/" + invoiceId + "/confirmar-pix", request, result -> {
            if (result.optBoolean("confirmado", false)
                    && "confirmado".equals(result.optString("situacao"))) {
                showPixReceipt(result);
                return;
            }
            String state = result.optString("situacao");
            String detail;
            switch (state) {
                case "aguardando": detail = "O PIX ainda não foi confirmado no IXC. " +
                        "A atualização pode demorar conforme o banco. Verifique novamente."; break;
                case "outro_meio": detail = "A cobrança consta recebida, mas não foi " +
                        "identificada como PIX. Não será emitida confirmação PIX."; break;
                case "parcial": detail = "A cobrança possui saldo em aberto. " +
                        "Não é possível emitir confirmação de quitação."; break;
                case "estornado": detail = "O pagamento consta estornado. " +
                        "Não será gerado comprovante."; break;
                case "cancelado": detail = "A cobrança está cancelada no IXC."; break;
                default: detail = "Não foi possível comprovar o recebimento via PIX " +
                        "com os dados atuais do IXC. Consulte o financeiro."; break;
            }
            dialog(detail);
        });
    }

    private void showPixReceipt(JSONObject receipt) {
        screenType = "payment";
        closePdf();
        initScreen("Pagamento confirmado", "A baixa via PIX foi confirmada no IXC.", true);
        TextView success = label("✓ PIX RECEBIDO", 20, GREEN, true);
        success.setGravity(Gravity.CENTER);
        success.setPadding(dp(10), dp(20), dp(10), dp(20));
        success.setBackground(shape(Color.WHITE, 14, 0));
        content.addView(success, margins(-1, -2, 14));

        String amount = receipt.optString("valor_recebido");
        String paymentDate = receipt.optString("data_pagamento");
        String invoiceId = receipt.optString("id");
        content.addView(label("Recebido: " + money(amount), 17, NAVY, true),
                margins(-1, -2, 9));
        content.addView(label("Fatura IXC nº " + invoiceId, 14, NAVY, false),
                margins(-1, -2, 9));
        if (!paymentDate.isEmpty()) {
            content.addView(label("Pagamento: " + paymentDate, 14, NAVY, false),
                    margins(-1, -2, 9));
        }
        TextView note = label(
                "Confirmação informativa baseada no registro do IXC. " +
                "Não substitui o comprovante bancário do pagador.", 12, MUTED, false);
        content.addView(note, margins(-1, -2, 16));

        primary("Enviar confirmação pelo WhatsApp", v -> {
            if (!isSessionValid()) {
                showHome();
                dialog("Sessão expirada. Consulte novamente para emitir confirmação.");
                return;
            }
            try {
                createReceiptPdf(receipt);
                sharePixConfirmation(receipt);
            } catch (Exception ex) {
                dialog("Não foi possível preparar a confirmação de recebimento.");
            }
        });
        primary("Voltar às faturas", v -> backToInvoicesOrHome());
    }

    private void createReceiptPdf(JSONObject receipt) throws IOException {
        if (!receipt.optBoolean("confirmado") ||
                !"confirmado".equals(receipt.optString("situacao"))) {
            throw new IOException("Pagamento não confirmado");
        }
        closePdf();
        File directory = new File(getCacheDir(), "boletos");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Sem cache");
        tempPdf = new File(directory, "confirmacao_pix.pdf");

        PdfDocument document = new PdfDocument();
        try {
            PdfDocument.PageInfo pageInfo =
                    new PdfDocument.PageInfo.Builder(595, 842, 1).create();
            PdfDocument.Page page = document.startPage(pageInfo);
            Canvas canvas = page.getCanvas();
            canvas.drawColor(Color.WHITE);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(NAVY);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            paint.setTextSize(26);
            canvas.drawText("ISM Telecom", 42, 74, paint);
            paint.setTextSize(17);
            canvas.drawText("Confirmação de recebimento - PIX", 42, 120, paint);

            paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            paint.setTextSize(14);
            canvas.drawText("Status: CONFIRMADO NO IXC", 42, 166, paint);
            canvas.drawText("Fatura IXC: " + receipt.optString("id"), 42, 197, paint);
            canvas.drawText("Documento: " + receipt.optString("documento"), 42, 228, paint);
            canvas.drawText("Valor recebido: " +
                    money(receipt.optString("valor_recebido")), 42, 259, paint);
            String date = receipt.optString("data_pagamento");
            canvas.drawText("Data de pagamento: " +
                    (date.isEmpty() ? "Não informada pelo IXC" : date), 42, 290, paint);
            canvas.drawText("Verificado em: " +
                    receipt.optString("verificado_em"), 42, 321, paint);
            String txid = receipt.optString("pix_txid");
            if (!txid.isEmpty()) {
                paint.setTextSize(10);
                // Identificadores muito longos não devem exceder os limites da página.
                if (txid.length() > 66) txid = txid.substring(0, 66) + "...";
                canvas.drawText("TxID: " + txid, 42, 352, paint);
            }
            paint.setTextSize(13);
            paint.setColor(MUTED);
            canvas.drawText("Documento informativo emitido a partir da baixa", 42, 432, paint);
            canvas.drawText("registrada no IXC. Não substitui o comprovante", 42, 451, paint);
            canvas.drawText("original fornecido pelo banco do pagador.", 42, 470, paint);
            document.finishPage(page);
            try (FileOutputStream out = new FileOutputStream(tempPdf)) {
                document.writeTo(out);
            }
        } finally {
            document.close();
        }
    }

    private void sharePixConfirmation(JSONObject receipt) {
        if (tempPdf == null || !tempPdf.exists()) {
            dialog("Arquivo de confirmação indisponível.");
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(
                    this, BuildConfig.APPLICATION_ID + ".fileprovider", tempPdf);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/pdf");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.setExtra(Intent.EXTRA_TEXT,
                    "ISM Telecom: PIX confirmado no IXC para a fatura " +
                    receipt.optString("id") + ". Valor recebido: " +
                    money(receipt.optString("valor_recebido")) + ".");
            send.setClipData(ClipData.newUri(getContentResolver(), "Confirmação PIX", uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            // O técnico escolhe o contato. Não enviamos automaticamente a terceiros.
            Intent whatsapp = new Intent(send);
            whatsapp.setPackage("com.whatsapp");
            try {
                startActivity(whatsapp);
            } catch (ActivityNotFoundException normalNotInstalled) {
                Intent business = new Intent(send);
                business.setPackage("com.whatsapp.w4b");
                try {
                    startActivity(business);
                } catch (ActivityNotFoundException businessNotInstalled) {
                    startActivity(Intent.createChooser(send,
                            "WhatsApp ou outro aplicativo para enviar a confirmação"));
                }
            }
        } catch (Exception ex) {
            dialog("Não há aplicativo disponível para compartilhar a confirmação.");
        }
    }

    private void displayQr(String code) {
        if (code.isEmpty()) return;
        try {
            BitMatrix bits = new MultiFormatWriter().encode(code, BarcodeFormat.QR_CODE, 280, 280);
            Bitmap image = Bitmap.createBitmap(280, 280, Bitmap.Config.ARGB_8888);
            for (int x = 0; x < 280; x++) for (int y = 0; y < 280; y++)
                image.setPixel(x, y, bits.get(x, y) ? Color.BLACK : Color.WHITE);
            ImageView qr = new ImageView(this);
            qr.setImageBitmap(image);
            qr.setAdjustViewBounds(true);
            content.addView(qr, margins(-1, dp(258), 12));
        } catch (Exception ex) {
            dialog("Não foi possível apresentar o QR Code.");
        }
    }

    private void displayCode(String code) {
        TextView value = label(code, 15, NAVY, false);
        value.setTextIsSelectable(true);
        value.setPadding(dp(12), dp(14), dp(12), dp(14));
        value.setBackground(shape(Color.WHITE, 10, 0));
        content.addView(value, margins(-1, -2, 12));
        primary("Copiar código", v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Código de pagamento", code));
            dialog("Código copiado. Confira os dados no aplicativo do banco.");
        });
    }

    private void savePdf(String encoded) throws Exception {
        byte[] pdf = Base64.getDecoder().decode(encoded);
        if (pdf.length < 5 || pdf.length > 8 * 1024 * 1024
                || pdf[0] != '%' || pdf[1] != 'P' || pdf[2] != 'D'
                || pdf[3] != 'F' || pdf[4] != '-') throw new IOException("PDF inválido");
        closePdf();
        File directory = new File(getCacheDir(), "boletos");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cache indisponível");
        tempPdf = new File(directory, "boleto_temporario.pdf");
        try (FileOutputStream out = new FileOutputStream(tempPdf)) { out.write(pdf); }
    }

    private void displayPdf(String encoded) throws Exception {
        savePdf(encoded);
        pdfDescriptor = ParcelFileDescriptor.open(tempPdf, ParcelFileDescriptor.MODE_READ_ONLY);
        renderer = new PdfRenderer(pdfDescriptor);
        currentPage = 0;
        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        content.addView(image);
        TextView pageInfo = label("", 12, NAVY, true);
        content.addView(pageInfo);
        Runnable render = () -> {
            if (renderer == null) return;
            try (PdfRenderer.Page page = renderer.openPage(currentPage)) {
                int width = 620;
                int height = Math.max(1, (int) ((float) page.getHeight() / page.getWidth() * width));
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                image.setImageBitmap(bitmap);
                pageInfo.setText("Página " + (currentPage + 1) + " de " + renderer.getPageCount());
            } catch (Exception ex) { dialog("Falha ao visualizar o boleto."); }
        };
        render.run();
        Button next = button("Próxima página", NAVY, false);
        next.setOnClickListener(v -> {
            if (renderer != null) {
                currentPage = (currentPage + 1) % renderer.getPageCount();
                render.run();
            }
        });
        content.addView(next, margins(-1, dp(45), 12));
    }

    private void sharePdf() {
        if (tempPdf == null || !tempPdf.exists()) {
            dialog("Boleto indisponível. Abra novamente."); return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(
                    this, BuildConfig.APPLICATION_ID + ".fileprovider", tempPdf);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/pdf");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.setClipData(ClipData.newUri(getContentResolver(), "Boleto", uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Enviar boleto"));
        } catch (Exception ex) {
            dialog("Não existe aplicativo compatível para enviar o PDF.");
        }
    }

    private void printPdf() {
        if (tempPdf == null || !tempPdf.exists()) {
            dialog("Boleto indisponível. Abra novamente."); return;
        }
        final File file = tempPdf;
        try {
            PrintManager printer = (PrintManager) getSystemService(Context.PRINT_SERVICE);
            printer.print("Boleto ISM Telecom", new PrintDocumentAdapter() {
                @Override public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                        CancellationSignal cancellation, LayoutResultCallback callback, Bundle extras) {
                    if (cancellation.isCanceled()) { callback.onLayoutCancelled(); return; }
                    PrintDocumentInfo info = new PrintDocumentInfo.Builder("boleto.pdf")
                            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                            .build();
                    callback.onLayoutFinished(info, true);
                }

                @Override public void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
                        CancellationSignal cancellation, WriteResultCallback callback) {
                    try (InputStream from = new FileInputStream(file);
                         OutputStream to = new FileOutputStream(destination.getFileDescriptor())) {
                        byte[] block = new byte[8192];
                        int count;
                        while ((count = from.read(block)) != -1) {
                            if (cancellation.isCanceled()) {
                                callback.onWriteCancelled(); return;
                            }
                            to.write(block, 0, count);
                        }
                        to.flush();
                        callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
                    } catch (Exception ex) { callback.onWriteFailed("Falha ao preparar impressão."); }
                }
            }, null);
        } catch (Exception ex) {
            dialog("Impressão exige serviço Android compatível. A impressora interna Gertec depende do SDK oficial.");
        }
    }

    private void showLead() {
        screenType = "lead";
        resetSession();
        closePdf();
        initScreen("Quero contratar", "Preencha os dados para nossa equipe comercial entrar em contato.", true);
        EditText name = field("Nome completo", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText phone = field("Contato (DDD + telefone)", InputType.TYPE_CLASS_PHONE);
        EditText cpf = field("CPF do interessado", InputType.TYPE_CLASS_NUMBER);
        EditText customerId = field("ID Cliente (opcional)", InputType.TYPE_CLASS_TEXT);
        EditText tech = field("Identificação do técnico", InputType.TYPE_CLASS_TEXT);
        CheckBox consent = new CheckBox(this);
        consent.setText("O interessado autorizou o contato comercial da ISM Telecom.");
        consent.setTextSize(13);
        consent.setTextColor(NAVY);
        content.addView(consent, margins(-1, -2, 12));

        primary("Enviar para o comercial", v -> {
            String nameValue = name.getText().toString().trim();
            String phoneValue = digits(phone.getText().toString());
            String cpfValue = digits(cpf.getText().toString());
            String techValue = tech.getText().toString().trim();
            if (nameValue.length() < 2 || cpfValue.length() != 11
                    || (phoneValue.length() != 10 && phoneValue.length() != 11)
                    || techValue.length() < 2 || !consent.isChecked()) {
                dialog("Preencha nome, CPF, telefone, identificação do técnico e consentimento.");
                return;
            }
            JSONObject data = new JSONObject();
            try {
                data.put("nome", nameValue);
                data.put("cpf", cpfValue);
                data.put("telefone", phoneValue);
                data.put("id_cliente", customerId.getText().toString().trim());
                data.put("tecnico", techValue);
                data.put("consentimento", true);
            } catch (JSONException ignored) {}
            hideKeyboard(tech);
            call("/api/v1/interessados", data, response -> {
                screenType = "lead-confirmation";
                initScreen("Solicitação enviada", "O contato foi encaminhado ao comercial.", true);
                content.addView(label("Protocolo: " + response.optString("protocolo"), 16, NAVY, true));
            });
        });
    }

    private void hideKeyboard(View view) {
        InputMethodManager keyboard = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(view.getWindowToken(), 0);
        view.clearFocus();
    }

    private interface Success { void done(JSONObject response); }

    private void call(String path, JSONObject data, Success onSuccess) {
        if (loading) return;
        final String secretKey = terminalKey();
        final String baseUrl = apiBase();
        if (!baseUrl.startsWith("https://") || secretKey.length() < 32) {
            showTerminalSetup();
            dialog("Configure a chave do terminal para consultar o IXC.");
            return;
        }
        final int epoch = requestEpoch;
        loading = true;
        ProgressBar progress = new ProgressBar(this);
        content.addView(progress, margins(-1, dp(42), 10));
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(baseUrl.replaceAll("/+$", "") + path).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(30_000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-Terminal-Key", secretKey);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(data.toString().getBytes(StandardCharsets.UTF_8));
                }
                int status = conn.getResponseCode();
                InputStream stream = status < 400 ? conn.getInputStream() : conn.getErrorStream();
                if (stream == null) throw new IOException("Sem resposta");
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                try (InputStream input = stream) {
                    byte[] bytes = new byte[8192];
                    int read;
                    while ((read = input.read(bytes)) != -1) {
                        buffer.write(bytes, 0, read);
                        if (buffer.size() > 12 * 1024 * 1024) throw new IOException("Resposta muito grande");
                    }
                }
                JSONObject result = new JSONObject(buffer.toString("UTF-8"));
                if (status >= 400) throw new IOException(result.optString("detail", "Consulta indisponível"));
                ui.post(() -> {
                    loading = false;
                    removeProgress(progress);
                    if (epoch != requestEpoch) return;
                    if (path.contains("/api/v1/faturas/") && !isSessionValid()) {
                        showHome();
                        dialog("Sessão expirada. Faça nova consulta.");
                        return;
                    }
                    onSuccess.done(result);
                });
            } catch (Exception error) {
                String message = error.getMessage() == null ? "Falha na comunicação" : error.getMessage();
                ui.post(() -> {
                    loading = false;
                    removeProgress(progress);
                    if (epoch == requestEpoch) dialog(message);
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private void removeProgress(ProgressBar progress) {
        if (progress.getParent() instanceof ViewGroup) {
            ((ViewGroup) progress.getParent()).removeView(progress);
        }
    }

    private void dialog(String text) {
        new AlertDialog.Builder(this).setTitle("ISM Telecom").setMessage(text)
                .setPositiveButton("OK", (dialog, which) -> {}).show();
    }

    private void closePdf() {
        try { if (renderer != null) renderer.close(); } catch (Exception ignored) {}
        renderer = null;
        try { if (pdfDescriptor != null) pdfDescriptor.close(); } catch (Exception ignored) {}
        pdfDescriptor = null;
        if (tempPdf != null) { tempPdf.delete(); tempPdf = null; }
    }

    @Override public void onBackPressed() { backToInvoicesOrHome(); }

    @Override protected void onDestroy() {
        resetSession();
        closePdf();
        executor.shutdownNow();
        super.onDestroy();
    }
}
