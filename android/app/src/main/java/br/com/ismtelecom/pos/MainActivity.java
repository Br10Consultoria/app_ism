package br.com.ismtelecom.pos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
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
            TextView arrow = label("‹", 32, CYAN, false);
            arrow.setGravity(Gravity.CENTER);
            arrow.setOnClickListener(v -> backToInvoicesOrHome());
            header.addView(arrow, new LinearLayout.LayoutParams(dp(44), dp(58)));
        }
        View brand = logo(back ? 58 : 102);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(0, dp(back ? 58 : 102), 1);
        header.addView(brand, logoParams);
        if (back) {
            TextView close = label("×", 30, NAVY, false);
            close.setGravity(Gravity.CENTER);
            close.setOnClickListener(v -> showHome());
            header.addView(close, new LinearLayout.LayoutParams(dp(44), dp(58)));
        }
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
        if ("payment".equals(screenType) && invoiceResponse != null && isSessionValid()) renderInvoices();
        else showHome();
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
        if (!BuildConfig.API_BASE.startsWith("https://") || BuildConfig.TERMINAL_KEY.length() < 32) {
            dialog("Configure o domínio HTTPS e a chave do terminal no APK."); return;
        }
        final int epoch = requestEpoch;
        loading = true;
        ProgressBar progress = new ProgressBar(this);
        content.addView(progress, margins(-1, dp(42), 10));
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(BuildConfig.API_BASE.replaceAll("/+$", "") + path).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(30_000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-Terminal-Key", BuildConfig.TERMINAL_KEY);
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
