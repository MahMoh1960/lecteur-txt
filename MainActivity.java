package ma.lecteurtxt;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lecteur TXT : lecture, recherche et édition simple de fichiers texte (français / arabe). */
public class MainActivity extends Activity {

    static final int REQ_OPEN = 1;
    static final int REQ_SAVE_AS = 2;
    static final int REQ_PERM = 3;

    static final int MAX_CHUNK = 3000;
    static final long MAX_BYTES = 40L * 1024 * 1024;
    static final int MAX_EDIT_CHARS = 3000000;
    static final int MAX_MATCHES = 20000;
    static final int MAX_CLIP = 400000;
    static final int MAX_SHARE_TEXT = 100000;
    static final int LIVE_SEARCH_LIMIT = 500000;

    static final int MODE_HOME = 0;
    static final int MODE_READ = 1;
    static final int MODE_EDIT = 2;

    // {fond, texte, barre}
    static final int[][] THEMES = {
            {0xFFFFFFFF, 0xFF202124, 0xFF1E5AA8},
            {0xFF121212, 0xFFE3E3E3, 0xFF222222},
            {0xFFF4ECD8, 0xFF4B3A2A, 0xFF8A6A47}
    };
    static final String[] THEME_NAMES = {"Clair", "Sombre", "Sépia"};
    static final int HL = 0xFFFFE082;
    static final int HL_CUR = 0xFFFF9800;
    static final int ACCENT = 0xFF2B6CB0;

    // ----- Index de lignes (les lignes très longues sont découpées pour rester fluides) -----
    static final class LineIndex {
        int[] s = new int[256];
        int[] e = new int[256];
        int n;

        void add(int a, int b) {
            if (n == s.length) {
                s = Arrays.copyOf(s, n * 2);
                e = Arrays.copyOf(e, n * 2);
            }
            s[n] = a;
            e[n] = b;
            n++;
        }
    }

    static LineIndex index(String t) {
        LineIndex li = new LineIndex();
        int len = t.length();
        int pos = 0;
        while (pos <= len) {
            int nl = t.indexOf('\n', pos);
            int end = nl < 0 ? len : nl;
            int s = pos;
            while (end - s > MAX_CHUNK) {
                int cut = s + MAX_CHUNK;
                int sp = t.lastIndexOf(' ', cut);
                if (sp > s + MAX_CHUNK / 2) {
                    cut = sp + 1;
                } else if (Character.isHighSurrogate(t.charAt(cut - 1))) {
                    cut--;
                }
                li.add(s, cut);
                s = cut;
            }
            li.add(s, end);
            if (nl < 0) {
                break;
            }
            pos = nl + 1;
        }
        return li;
    }

    // ----- État -----
    private SharedPreferences sp;
    private RecentStore recents;
    private float textSp = 18f;
    private float pendingScale = 1f;
    private int theme = 0;

    private Uri curUri;
    private String curName = "";
    private String text = "";
    private Charset charset = StandardCharsets.UTF_8;
    private boolean bom;
    private boolean crlf;
    private boolean modified;
    private boolean truncated;
    private boolean hasFile;
    private boolean editing;
    private boolean dirtyIndex;
    private boolean suppress;
    private long fileBytes;
    private int loadGen;
    private int mode = MODE_HOME;
    private LineIndex idx = index("");
    private Uri pendingUri;
    private Runnable pendingAfter;

    // recherche
    private int[] mstart = new int[0];
    private int mcount;
    private int mcur = -1;
    private int mlen;
    private String lastQuery = "";

    // ----- Vues -----
    private LinearLayout root;
    private LinearLayout bar;
    private LinearLayout searchBar;
    private TextView titleView;
    private ImageButton btnSearch;
    private ImageButton btnEdit;
    private ImageButton btnSave;
    private ImageButton btnMore;
    private EditText searchInput;
    private TextView countView;
    private TextView btnPrev;
    private TextView btnNext;
    private TextView btnClose;
    private ZoomLayout content;
    private ListView listView;
    private EditText editor;
    private LinearLayout home;
    private TextView homeTitle;
    private TextView homeHint;
    private TextView recTitle;
    private TextView homeEmpty;
    private ListView recList;
    private LineAdapter adapter;
    private RecentAdapter recAdapter;

    // =====================================================================
    // Cycle de vie
    // =====================================================================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sp = getSharedPreferences("lecteur", MODE_PRIVATE);
        recents = new RecentStore(sp);
        textSp = sp.getFloat("size", 18f);
        theme = sp.getInt("theme", 0);
        if (theme < 0 || theme >= THEMES.length) {
            theme = 0;
        }
        buildUi();
        applyTheme();
        showMode(MODE_HOME);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sp.edit().putFloat("size", textSp).putInt("theme", theme).apply();
        if (hasFile && curUri != null) {
            recents.setOffset(curUri.toString(), currentOffset());
        }
    }

    @Override
    public void onBackPressed() {
        if (searchBar.getVisibility() == View.VISIBLE) {
            closeSearch();
            return;
        }
        if (editing) {
            leaveEdit();
            return;
        }
        if (modified) {
            guardUnsaved(new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            });
            return;
        }
        super.onBackPressed();
    }

    // =====================================================================
    // Interface
    // =====================================================================
    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private ImageButton iconButton(int res, View.OnClickListener l) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) {
            b.setBackgroundResource(tv.resourceId);
        } else {
            b.setBackground(null);
        }
        b.setOnClickListener(l);
        return b;
    }

    private TextView textButton(String label, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(18);
        t.setTextColor(0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(l);
        return t;
    }

    private TextView accentButton(String label, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(16);
        t.setTextColor(0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(ACCENT);
        g.setCornerRadius(dp(8));
        t.setBackground(g);
        t.setClickable(true);
        t.setOnClickListener(l);
        return t;
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // Barre supérieure
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        titleView = new TextView(this);
        titleView.setTextSize(18);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setPadding(dp(16), 0, dp(8), 0);
        titleView.setText("Lecteur TXT");
        bar.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnSearch = iconButton(android.R.drawable.ic_menu_search, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSearch();
            }
        });
        btnEdit = iconButton(android.R.drawable.ic_menu_edit, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleEdit();
            }
        });
        btnSave = iconButton(android.R.drawable.ic_menu_save, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save(null);
            }
        });
        btnMore = iconButton(android.R.drawable.ic_menu_more, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(v);
            }
        });
        bar.addView(btnSearch, new LinearLayout.LayoutParams(dp(48), dp(56)));
        bar.addView(btnEdit, new LinearLayout.LayoutParams(dp(48), dp(56)));
        bar.addView(btnSave, new LinearLayout.LayoutParams(dp(48), dp(56)));
        bar.addView(btnMore, new LinearLayout.LayoutParams(dp(48), dp(56)));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        // Barre de recherche
        searchBar = new LinearLayout(this);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setPadding(dp(12), 0, 0, dp(6));
        searchBar.setVisibility(View.GONE);
        searchInput = new EditText(this);
        searchInput.setHint("Rechercher…");
        searchInput.setSingleLine(true);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setTextSize(16);
        searchInput.setBackgroundColor(0x22FFFFFF);
        searchInput.setPadding(dp(10), dp(6), dp(10), dp(6));
        searchInput.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (text.length() < LIVE_SEARCH_LIMIT) {
                    runSearch(true);
                }
            }
        });
        searchInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                stepSearch(1);
                hideKeyboard();
                return true;
            }
        });
        searchBar.addView(searchInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        countView = new TextView(this);
        countView.setTextSize(13);
        countView.setPadding(dp(8), 0, dp(4), 0);
        searchBar.addView(countView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        btnPrev = textButton("▲", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stepSearch(-1);
            }
        });
        btnNext = textButton("▼", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stepSearch(1);
            }
        });
        btnClose = textButton("✕", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSearch();
            }
        });
        searchBar.addView(btnPrev, new LinearLayout.LayoutParams(dp(42), dp(42)));
        searchBar.addView(btnNext, new LinearLayout.LayoutParams(dp(42), dp(42)));
        searchBar.addView(btnClose, new LinearLayout.LayoutParams(dp(42), dp(42)));
        root.addView(searchBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Contenu
        content = new ZoomLayout(this);
        content.setListener(new ZoomLayout.Listener() {
            @Override
            public void onScale(float factor) {
                scaleBy(factor);
            }
        });
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        listView = new ListView(this);
        adapter = new LineAdapter();
        listView.setAdapter(adapter);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setFastScrollEnabled(true);
        listView.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                lineActions(position);
                return true;
            }
        });

        editor = new EditText(this);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setBackground(null);
        editor.setPadding(dp(14), dp(8), dp(14), dp(8));
        editor.setLineSpacing(0f, 1.2f);
        editor.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        editor.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
        editor.setHorizontallyScrolling(false);
        editor.setVerticalScrollBarEnabled(true);
        editor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (suppress) {
                    return;
                }
                dirtyIndex = true;
                if (!modified) {
                    modified = true;
                    updateTitle();
                    updateButtons();
                }
                if (mcount > 0) {
                    mcount = 0;
                    mcur = -1;
                    countView.setText("");
                }
            }
        });

        home = buildHome();
        ViewGroup.LayoutParams mp = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        content.addView(home, mp);
        content.addView(listView, new ViewGroup.LayoutParams(mp));
        content.addView(editor, new ViewGroup.LayoutParams(mp));

        setContentView(root);
    }

    private LinearLayout buildHome() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setPadding(dp(16), dp(16), dp(16), dp(8));

        homeTitle = new TextView(this);
        homeTitle.setText("Lecteur TXT");
        homeTitle.setTextSize(24);
        homeTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        h.addView(homeTitle);

        homeHint = new TextView(this);
        homeHint.setText("Ouvrez un fichier texte ici, ou appuyez sur un fichier .txt dans votre gestionnaire de fichiers puis choisissez « Lecteur TXT » dans « Ouvrir avec ».");
        homeHint.setTextSize(14);
        homeHint.setPadding(0, dp(8), 0, dp(16));
        h.addView(homeHint);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView b1 = accentButton("Ouvrir un fichier", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFile();
            }
        });
        TextView b2 = accentButton("Nouveau texte", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newDoc("", "Sans titre.txt");
            }
        });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp1.rightMargin = dp(6);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp2.leftMargin = dp(6);
        row.addView(b1, lp1);
        row.addView(b2, lp2);
        h.addView(row);

        recTitle = new TextView(this);
        recTitle.setText("Fichiers récents");
        recTitle.setTextSize(16);
        recTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        recTitle.setPadding(0, dp(20), 0, dp(6));
        h.addView(recTitle);

        homeEmpty = new TextView(this);
        homeEmpty.setText("Aucun fichier récent.");
        homeEmpty.setTextSize(14);
        h.addView(homeEmpty);

        recList = new ListView(this);
        recAdapter = new RecentAdapter();
        recList.setAdapter(recAdapter);
        recList.setDivider(null);
        recList.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                RecentStore.Item it = recAdapter.items.get(position);
                openUri(Uri.parse(it.uri), null);
            }
        });
        recList.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                final RecentStore.Item it = recAdapter.items.get(position);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(it.name)
                        .setMessage("Retirer ce fichier de la liste des récents ?")
                        .setPositiveButton("Retirer", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                recents.remove(it.uri);
                                recAdapter.refresh();
                            }
                        })
                        .setNegativeButton("Annuler", null)
                        .show();
                return true;
            }
        });
        h.addView(recList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return h;
    }

    private void applyTheme() {
        int bg = THEMES[theme][0];
        int fg = THEMES[theme][1];
        int barC = THEMES[theme][2];
        root.setBackgroundColor(bg);
        bar.setBackgroundColor(barC);
        searchBar.setBackgroundColor(barC);
        titleView.setTextColor(0xFFFFFFFF);
        btnSearch.setColorFilter(0xFFFFFFFF);
        btnEdit.setColorFilter(0xFFFFFFFF);
        btnSave.setColorFilter(0xFFFFFFFF);
        btnMore.setColorFilter(0xFFFFFFFF);
        searchInput.setTextColor(0xFFFFFFFF);
        searchInput.setHintTextColor(0xAAFFFFFF);
        countView.setTextColor(0xFFFFFFFF);
        content.setBackgroundColor(bg);
        listView.setBackgroundColor(bg);
        editor.setTextColor(fg);
        editor.setBackgroundColor(bg);
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
        home.setBackgroundColor(bg);
        homeTitle.setTextColor(fg);
        homeHint.setTextColor(fg);
        recTitle.setTextColor(fg);
        homeEmpty.setTextColor(fg);
        getWindow().setStatusBarColor(barC);
        getWindow().setNavigationBarColor(barC);
        adapter.notifyDataSetChanged();
        recAdapter.refresh();
    }

    private void showMode(int m) {
        mode = m;
        home.setVisibility(m == MODE_HOME ? View.VISIBLE : View.GONE);
        listView.setVisibility(m == MODE_READ ? View.VISIBLE : View.GONE);
        editor.setVisibility(m == MODE_EDIT ? View.VISIBLE : View.GONE);
        if (m == MODE_HOME) {
            recAdapter.refresh();
        }
        updateTitle();
        updateButtons();
    }

    private void updateTitle() {
        if (!hasFile) {
            titleView.setText("Lecteur TXT");
        } else {
            titleView.setText((modified ? "• " : "") + curName);
        }
    }

    private void updateButtons() {
        btnSave.setVisibility(hasFile && modified ? View.VISIBLE : View.GONE);
        btnEdit.setVisibility(hasFile ? View.VISIBLE : View.GONE);
        btnSearch.setVisibility(hasFile ? View.VISIBLE : View.GONE);
        btnEdit.setImageResource(editing ? android.R.drawable.ic_menu_view : android.R.drawable.ic_menu_edit);
        btnEdit.setColorFilter(0xFFFFFFFF);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
        }
    }

    // =====================================================================
    // Adaptateurs
    // =====================================================================
    class LineAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return hasFile ? idx.n : 0;
        }

        @Override
        public Object getItem(int p) {
            return null;
        }

        @Override
        public long getItemId(int p) {
            return p;
        }

        @Override
        public View getView(int p, View cv, ViewGroup parent) {
            TextView tv = (TextView) cv;
            if (tv == null) {
                tv = new TextView(MainActivity.this);
                tv.setPadding(dp(14), dp(1), dp(14), dp(1));
                tv.setLineSpacing(0f, 1.2f);
                tv.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
                tv.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
            }
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
            tv.setTextColor(THEMES[theme][1]);
            int b = Math.min(idx.e[p], text.length());
            int a = Math.min(idx.s[p], b);
            CharSequence cs = text.substring(a, b);
            if (mcount > 0) {
                int k = lowerBound(mstart, mcount, a);
                if (k < mcount && mstart[k] < b) {
                    SpannableString ss = new SpannableString(cs);
                    for (; k < mcount && mstart[k] < b; k++) {
                        int st = mstart[k] - a;
                        int en = Math.min(mstart[k] + mlen, b) - a;
                        ss.setSpan(new BackgroundColorSpan(k == mcur ? HL_CUR : HL), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        ss.setSpan(new ForegroundColorSpan(0xFF000000), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    cs = ss;
                }
            }
            tv.setText(cs);
            return tv;
        }
    }

    class RecentAdapter extends BaseAdapter {
        List<RecentStore.Item> items = new java.util.ArrayList<>();
        final SimpleDateFormat fmt = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE);

        void refresh() {
            items = recents.load();
            homeEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int p) {
            return items.get(p);
        }

        @Override
        public long getItemId(int p) {
            return p;
        }

        @Override
        public View getView(int p, View cv, ViewGroup parent) {
            LinearLayout row;
            TextView name;
            TextView sub;
            if (cv == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(0, dp(8), 0, dp(8));
                name = new TextView(MainActivity.this);
                name.setTextSize(17);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                sub = new TextView(MainActivity.this);
                sub.setTextSize(12);
                row.addView(name);
                row.addView(sub);
                row.setTag(new TextView[]{name, sub});
            } else {
                row = (LinearLayout) cv;
                TextView[] t = (TextView[]) row.getTag();
                name = t[0];
                sub = t[1];
            }
            RecentStore.Item it = items.get(p);
            name.setText(it.name);
            sub.setText(fmt.format(new Date(it.time)));
            name.setTextColor(THEMES[theme][1]);
            sub.setTextColor((THEMES[theme][1] & 0x00FFFFFF) | 0xAA000000);
            return row;
        }
    }

    // =====================================================================
    // Ouverture
    // =====================================================================
    private void handleIntent(Intent in) {
        if (in == null) {
            return;
        }
        String a = in.getAction();
        if (Intent.ACTION_VIEW.equals(a) || Intent.ACTION_EDIT.equals(a)) {
            Uri u = in.getData();
            if (u != null) {
                openUri(u, null);
            }
        } else if (Intent.ACTION_SEND.equals(a)) {
            @SuppressWarnings("deprecation")
            Uri u = (Uri) in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) {
                openUri(u, null);
            } else {
                CharSequence t = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (t != null) {
                    newDoc(t.toString(), "Texte partagé.txt");
                }
            }
        }
    }

    private void pickFile() {
        guardUnsaved(new Runnable() {
            @Override
            public void run() {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(i, REQ_OPEN);
                } catch (Exception e) {
                    toast("Aucun sélecteur de fichiers disponible.");
                }
            }
        });
    }

    private void openUri(final Uri uri, final Charset forced) {
        guardUnsaved(new Runnable() {
            @Override
            public void run() {
                doOpen(uri, forced);
            }
        });
    }

    private void doOpen(final Uri uri, final Charset forced) {
        if ("file".equals(uri.getScheme()) && Build.VERSION.SDK_INT <= 32
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingUri = uri;
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM);
            return;
        }
        final int gen = ++loadGen;
        titleView.setText("Chargement…");
        final String name = queryName(uri);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    tryPersist(uri);
                    long sz = querySize(uri);
                    boolean[] trunc = new boolean[1];
                    final byte[] raw = readAll(uri, sz, trunc);
                    final TextCodec.Result r = TextCodec.decode(raw, forced);
                    final LineIndex li = index(r.text);
                    final boolean tr = trunc[0];
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (gen == loadGen) {
                                applyLoaded(uri, name, raw.length, r, li, tr);
                            }
                        }
                    });
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (gen == loadGen) {
                                onLoadError(t);
                            }
                        }
                    });
                }
            }
        }).start();
    }

    private void onLoadError(Throwable t) {
        updateTitle();
        if (t instanceof OutOfMemoryError) {
            toast("Fichier trop volumineux pour la mémoire de l'appareil.");
        } else if (t instanceof SecurityException) {
            toast("Accès refusé. Rouvrez le fichier avec « Ouvrir un fichier ».");
        } else {
            toast("Impossible de lire le fichier : " + t.getMessage());
        }
    }

    private void applyLoaded(Uri uri, String name, long bytes, TextCodec.Result r, LineIndex li, boolean tr) {
        curUri = uri;
        String shown = name;
        if (looksCorrupted(name)) {
            String t = firstLineTitle(r.text);
            if (!t.isEmpty()) {
                shown = t + ".txt";
            }
        }
        curName = shown;
        text = r.text;
        charset = r.charset;
        bom = r.bom;
        crlf = r.crlf;
        idx = li;
        truncated = tr;
        fileBytes = bytes;
        modified = false;
        hasFile = true;
        editing = false;
        dirtyIndex = false;
        clearSearchState();
        showMode(MODE_READ);
        adapter.notifyDataSetChanged();
        final int off = recents.offsetOf(uri.toString());
        listView.post(new Runnable() {
            @Override
            public void run() {
                listView.setSelection(lineOf(off));
            }
        });
        recents.touch(uri.toString(), shown, off);
        if (tr) {
            toast("Fichier très volumineux : seuls les premiers 40 Mo sont affichés (lecture seule).");
        }
    }

    private void newDoc(final String content, final String name) {
        guardUnsaved(new Runnable() {
            @Override
            public void run() {
                curUri = null;
                curName = name;
                text = content;
                charset = StandardCharsets.UTF_8;
                bom = false;
                crlf = false;
                truncated = false;
                fileBytes = 0;
                idx = index(text);
                hasFile = true;
                modified = !content.isEmpty();
                clearSearchState();
                adapter.notifyDataSetChanged();
                editing = false;
                enterEdit();
            }
        });
    }

    private String queryName(Uri u) {
        String n = null;
        if ("content".equals(u.getScheme())) {
            Cursor c = null;
            try {
                c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
                if (c != null && c.moveToFirst()) {
                    n = c.getString(0);
                }
            } catch (Exception ignored) {
                // nom de repli ci-dessous
            } finally {
                if (c != null) {
                    c.close();
                }
            }
        }
        if (n == null || n.isEmpty()) {
            n = u.getLastPathSegment();
            if (n != null) {
                int i = n.lastIndexOf('/');
                if (i >= 0) {
                    n = n.substring(i + 1);
                }
            }
        }
        return (n == null || n.isEmpty()) ? "Sans titre.txt" : n;
    }

    private static final String FRENCH_CHARS = "àâäçèéêëîïôöùûüÿÀÂÄÇÈÉÊËÎÏÔÖÙÛÜŸœŒ«»°’‘“”–—…€";

    /** Vrai si le nom contient surtout des caractères illisibles (nom mal encodé dans le système de fichiers). */
    static boolean looksCorrupted(String name) {
        int dot = name.lastIndexOf('.');
        String base = (dot > 0 && name.length() - dot <= 6) ? name.substring(0, dot) : name;
        int odd = 0;
        int total = 0;
        for (int i = 0; i < base.length(); i++) {
            char ch = base.charAt(i);
            if (Character.isWhitespace(ch) || Character.isDigit(ch)) {
                continue;
            }
            total++;
            boolean arabic = ch >= 0x0600 && ch <= 0x06FF;
            if (ch >= 0x80 && !arabic && FRENCH_CHARS.indexOf(ch) < 0) {
                odd++;
            }
        }
        return odd >= 2 && odd * 2 >= total;
    }

    /** Première ligne non vide du texte, nettoyée pour servir de titre. */
    static String firstLineTitle(String t) {
        int pos = 0;
        int n = t.length();
        while (pos < n) {
            int nl = t.indexOf('\n', pos);
            int end = nl < 0 ? n : nl;
            String line = t.substring(pos, Math.min(end, pos + 400)).trim();
            if (!line.isEmpty()) {
                line = line.replaceAll("[\\\\/:*?\"<>|]", " ").replaceAll("\\s+", " ").trim();
                if (line.length() > 60) {
                    line = line.substring(0, 60).trim();
                }
                return line;
            }
            if (nl < 0) {
                break;
            }
            pos = nl + 1;
        }
        return "";
    }

    private long querySize(Uri u) {
        if ("file".equals(u.getScheme()) && u.getPath() != null) {
            return new File(u.getPath()).length();
        }
        Cursor c = null;
        try {
            c = getContentResolver().query(u, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getLong(0);
            }
        } catch (Exception ignored) {
            // taille inconnue
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return -1;
    }

    private void tryPersist(Uri u) {
        if (!"content".equals(u.getScheme())) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            try {
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
                // permission temporaire seulement
            }
        }
    }

    private byte[] readAll(Uri uri, long size, boolean[] trunc) throws IOException {
        InputStream is = getContentResolver().openInputStream(uri);
        if (is == null) {
            throw new IOException("flux introuvable");
        }
        try {
            if (size > 0) {
                int want = (int) Math.min(size, MAX_BYTES);
                byte[] buf = new byte[want];
                int got = 0;
                while (got < want) {
                    int r = is.read(buf, got, want - got);
                    if (r < 0) {
                        break;
                    }
                    got += r;
                }
                if (size > MAX_BYTES) {
                    trunc[0] = true;
                }
                return got == want ? buf : Arrays.copyOf(buf, got);
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 16);
            byte[] b = new byte[65536];
            long tot = 0;
            int r;
            while ((r = is.read(b)) > 0) {
                if (tot + r > MAX_BYTES) {
                    bo.write(b, 0, (int) (MAX_BYTES - tot));
                    trunc[0] = true;
                    break;
                }
                bo.write(b, 0, r);
                tot += r;
            }
            return bo.toByteArray();
        } finally {
            is.close();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_PERM) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && pendingUri != null) {
                doOpen(pendingUri, null);
            } else {
                toast("Autorisation refusée : impossible de lire ce fichier.");
            }
            pendingUri = null;
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) {
            if (req == REQ_SAVE_AS) {
                pendingAfter = null;
            }
            return;
        }
        Uri u = data.getData();
        if (req == REQ_OPEN) {
            doOpen(u, null);
        } else if (req == REQ_SAVE_AS) {
            tryPersist(u);
            curUri = u;
            curName = queryName(u);
            Runnable after = pendingAfter;
            pendingAfter = null;
            writeTo(u, after, false);
        }
    }

    // =====================================================================
    // Enregistrement
    // =====================================================================
    private void guardUnsaved(final Runnable next) {
        if (!modified) {
            next.run();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Modifications non enregistrées")
                .setMessage("Enregistrer « " + curName + " » avant de continuer ?")
                .setPositiveButton("Enregistrer", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        save(next);
                    }
                })
                .setNegativeButton("Ignorer", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        modified = false;
                        updateTitle();
                        updateButtons();
                        next.run();
                    }
                })
                .setNeutralButton("Annuler", null)
                .show();
    }

    private void save(Runnable after) {
        if (!hasFile) {
            return;
        }
        syncText();
        if (truncated) {
            toast("Fichier tronqué : enregistrement désactivé pour ne pas perdre de données.");
            return;
        }
        if (curUri == null) {
            saveAs(after);
            return;
        }
        writeTo(curUri, after, true);
    }

    private void saveAs(Runnable after) {
        if (!hasFile) {
            return;
        }
        syncText();
        pendingAfter = after;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(curName.toLowerCase(Locale.ROOT).endsWith(".txt") ? "text/plain" : "application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE, curName);
        try {
            startActivityForResult(i, REQ_SAVE_AS);
        } catch (Exception e) {
            toast("Aucun sélecteur de fichiers disponible.");
        }
    }

    private void writeTo(final Uri uri, final Runnable after, final boolean fallbackSaveAs) {
        if (!TextCodec.canEncode(text, charset, crlf)) {
            charset = StandardCharsets.UTF_8;
            bom = false;
            toast("Certains caractères ne sont pas gérés par l'encodage d'origine : enregistrement en UTF-8.");
        }
        final String snapshot = text;
        final Charset cs = charset;
        final boolean b = bom;
        final boolean c = crlf;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] data = TextCodec.encode(snapshot, cs, b, c);
                    OutputStream os = getContentResolver().openOutputStream(uri, "wt");
                    if (os == null) {
                        throw new IOException("ouverture impossible");
                    }
                    try {
                        os.write(data);
                        os.flush();
                    } finally {
                        os.close();
                    }
                    final long written = data.length;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            fileBytes = written;
                            syncText();
                            modified = !text.equals(snapshot);
                            updateTitle();
                            updateButtons();
                            recents.touch(uri.toString(), curName, currentOffset());
                            Toast.makeText(MainActivity.this, "Enregistré", Toast.LENGTH_SHORT).show();
                            if (after != null) {
                                after.run();
                            }
                        }
                    });
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (fallbackSaveAs) {
                                toast("Écriture impossible à cet emplacement : choisissez où enregistrer.");
                                saveAs(after);
                            } else {
                                toast("Échec de l'enregistrement : " + t.getMessage());
                            }
                        }
                    });
                }
            }
        }).start();
    }

    // =====================================================================
    // Mode lecture / édition
    // =====================================================================
    private void syncText() {
        if (editing) {
            text = editor.getText().toString();
        }
    }

    private void toggleEdit() {
        if (!hasFile) {
            return;
        }
        if (editing) {
            leaveEdit();
        } else {
            enterEdit();
        }
    }

    private void enterEdit() {
        if (!hasFile) {
            return;
        }
        if (truncated) {
            toast("Fichier tronqué : lecture seule.");
            return;
        }
        if (text.length() > MAX_EDIT_CHARS) {
            toast("Fichier trop volumineux pour l'édition (plus de 3 millions de caractères) : lecture seule.");
            return;
        }
        int off = currentOffset();
        suppress = true;
        editor.setText(text);
        suppress = false;
        editing = true;
        dirtyIndex = false;
        showMode(MODE_EDIT);
        editor.setSelection(Math.max(0, Math.min(off, editor.length())));
        editor.requestFocus();
    }

    private void leaveEdit() {
        syncText();
        int sel = editor.getSelectionStart();
        editing = false;
        hideKeyboard();
        if (dirtyIndex) {
            idx = index(text);
            dirtyIndex = false;
        }
        clearMatches();
        showMode(MODE_READ);
        adapter.notifyDataSetChanged();
        final int line = lineOf(Math.max(0, sel));
        listView.post(new Runnable() {
            @Override
            public void run() {
                listView.setSelection(line);
            }
        });
    }

    private int currentOffset() {
        if (!hasFile) {
            return 0;
        }
        if (editing) {
            return Math.max(0, editor.getSelectionStart());
        }
        int p = listView.getFirstVisiblePosition();
        if (p < 0 || p >= idx.n) {
            return 0;
        }
        return idx.s[p];
    }

    private int lineOf(int off) {
        int lo = 0;
        int hi = idx.n - 1;
        int ans = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (idx.s[mid] <= off) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    private static int lowerBound(int[] a, int n, int key) {
        int lo = 0;
        int hi = n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] < key) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    // =====================================================================
    // Zoom
    // =====================================================================
    private void scaleBy(float f) {
        pendingScale *= f;
        float ns = Math.max(10f, Math.min(56f, textSp * pendingScale));
        if (Math.abs(ns - textSp) >= 0.3f) {
            textSp = ns;
            pendingScale = 1f;
            applyTextSize();
        }
    }

    private void applyTextSize() {
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
        adapter.notifyDataSetChanged();
    }

    // =====================================================================
    // Recherche
    // =====================================================================
    private void toggleSearch() {
        if (!hasFile) {
            return;
        }
        if (searchBar.getVisibility() == View.VISIBLE) {
            closeSearch();
            return;
        }
        searchBar.setVisibility(View.VISIBLE);
        searchInput.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void closeSearch() {
        searchBar.setVisibility(View.GONE);
        hideKeyboard();
        clearMatches();
        lastQuery = "";
        searchInput.setText("");
    }

    private void clearSearchState() {
        searchBar.setVisibility(View.GONE);
        lastQuery = "";
        mcount = 0;
        mcur = -1;
        countView.setText("");
    }

    private void clearMatches() {
        mcount = 0;
        mcur = -1;
        countView.setText("");
        adapter.notifyDataSetChanged();
    }

    private void runSearch(boolean jump) {
        syncText();
        String q = searchInput.getText().toString();
        lastQuery = q;
        mcount = 0;
        mcur = -1;
        mlen = q.length();
        if (q.isEmpty() || text.isEmpty()) {
            countView.setText("");
            adapter.notifyDataSetChanged();
            return;
        }
        Matcher m = Pattern.compile(Pattern.quote(q), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text);
        int[] arr = new int[256];
        int c = 0;
        while (c < MAX_MATCHES && m.find()) {
            if (c == arr.length) {
                arr = Arrays.copyOf(arr, c * 2);
            }
            arr[c++] = m.start();
        }
        mstart = arr;
        mcount = c;
        if (c > 0) {
            int from = currentOffset();
            int k = lowerBound(mstart, c, from);
            mcur = k < c ? k : 0;
        }
        adapter.notifyDataSetChanged();
        updateCount();
        if (jump) {
            gotoMatch();
        }
    }

    private void stepSearch(int dir) {
        String q = searchInput.getText().toString();
        if (!q.equals(lastQuery) || (mcount == 0 && !q.isEmpty())) {
            runSearch(true);
            return;
        }
        if (mcount == 0) {
            return;
        }
        mcur = (mcur + dir + mcount) % mcount;
        adapter.notifyDataSetChanged();
        updateCount();
        gotoMatch();
    }

    private void updateCount() {
        if (searchInput.getText().length() == 0) {
            countView.setText("");
        } else if (mcount == 0) {
            countView.setText("0 résultat");
        } else {
            countView.setText((mcur + 1) + "/" + mcount + (mcount >= MAX_MATCHES ? "+" : ""));
        }
    }

    private void gotoMatch() {
        if (mcount == 0 || mcur < 0) {
            return;
        }
        int s = mstart[mcur];
        if (editing) {
            int len = editor.length();
            int st = Math.min(s, len);
            int en = Math.min(s + mlen, len);
            editor.setSelection(st, en);
            editor.bringPointIntoView(st);
        } else {
            listView.setSelectionFromTop(lineOf(s), dp(90));
        }
    }

    // =====================================================================
    // Menu et actions
    // =====================================================================
    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        android.view.Menu m = pm.getMenu();
        m.add(0, 1, 0, "Ouvrir un fichier");
        m.add(0, 2, 0, "Nouveau texte");
        m.add(0, 3, 0, "Fichiers récents");
        if (hasFile) {
            m.add(0, 4, 0, "Rechercher");
            m.add(0, 5, 0, editing ? "Mode lecture" : "Modifier");
            m.add(0, 6, 0, "Enregistrer");
            m.add(0, 7, 0, "Enregistrer sous…");
            boolean sel = editing && editor.getSelectionStart() != editor.getSelectionEnd();
            m.add(0, 11, 0, sel ? "Copier la sélection" : "Copier tout");
            m.add(0, 12, 0, sel ? "Partager la sélection" : "Partager");
            m.add(0, 13, 0, "Début du fichier");
            m.add(0, 14, 0, "Fin du fichier");
            m.add(0, 10, 0, "Encodage…");
            m.add(0, 15, 0, "Informations");
        }
        m.add(0, 8, 0, "Taille du texte");
        m.add(0, 9, 0, "Thème");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                onMenu(item.getItemId());
                return true;
            }
        });
        pm.show();
    }

    private void onMenu(int id) {
        switch (id) {
            case 1:
                pickFile();
                break;
            case 2:
                newDoc("", "Sans titre.txt");
                break;
            case 3:
                showRecentsDialog();
                break;
            case 4:
                toggleSearch();
                break;
            case 5:
                toggleEdit();
                break;
            case 6:
                save(null);
                break;
            case 7:
                saveAs(null);
                break;
            case 8:
                showSizeDialog();
                break;
            case 9:
                showThemeDialog();
                break;
            case 10:
                showEncodingDialog();
                break;
            case 11:
                copyText(selectedOrAll());
                break;
            case 12:
                shareText(selectedOrAll());
                break;
            case 13:
                goTo(true);
                break;
            case 14:
                goTo(false);
                break;
            case 15:
                showInfo();
                break;
            default:
                break;
        }
    }

    private String selectedOrAll() {
        syncText();
        if (editing) {
            int a = editor.getSelectionStart();
            int b = editor.getSelectionEnd();
            if (a != b) {
                return text.substring(Math.max(0, Math.min(a, b)), Math.min(text.length(), Math.max(a, b)));
            }
        }
        return text;
    }

    private void copyText(String s) {
        if (s.length() > MAX_CLIP) {
            toast("Texte trop volumineux pour le presse-papiers. Sélectionnez une partie en mode Modifier.");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("texte", s));
            Toast.makeText(this, "Copié", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareText(String s) {
        try {
            if (s.length() <= MAX_SHARE_TEXT) {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, s);
                i.putExtra(Intent.EXTRA_SUBJECT, curName);
                startActivity(Intent.createChooser(i, "Partager"));
            } else if (curUri != null && !modified && "content".equals(curUri.getScheme()) && s.length() == text.length()) {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_STREAM, curUri);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "Partager"));
            } else {
                toast("Texte trop volumineux pour un partage direct. Enregistrez le fichier puis partagez-le depuis votre gestionnaire de fichiers.");
            }
        } catch (Exception e) {
            toast("Partage impossible.");
        }
    }

    private void goTo(boolean start) {
        if (editing) {
            editor.setSelection(start ? 0 : editor.length());
            editor.bringPointIntoView(start ? 0 : editor.length());
        } else {
            listView.setSelection(start ? 0 : Math.max(0, idx.n - 1));
        }
    }

    private void lineActions(final int position) {
        if (position < 0 || position >= idx.n) {
            return;
        }
        final String line = text.substring(idx.s[position], idx.e[position]);
        final int off = idx.s[position];
        String[] items = {"Copier la ligne", "Partager la ligne", "Modifier à cet endroit"};
        new AlertDialog.Builder(this)
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) {
                            copyText(line);
                        } else if (which == 1) {
                            shareText(line);
                        } else {
                            enterEdit();
                            if (editing) {
                                int p = Math.min(off, editor.length());
                                editor.setSelection(p);
                                editor.bringPointIntoView(p);
                            }
                        }
                    }
                })
                .show();
    }

    private void showRecentsDialog() {
        final List<RecentStore.Item> l = recents.load();
        if (l.isEmpty()) {
            toast("Aucun fichier récent.");
            return;
        }
        String[] names = new String[l.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = l.get(i).name;
        }
        new AlertDialog.Builder(this)
                .setTitle("Fichiers récents")
                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        openUri(Uri.parse(l.get(which).uri), null);
                    }
                })
                .show();
    }

    private void showSizeDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(16), dp(24), dp(8));
        final TextView label = new TextView(this);
        label.setTextSize(16);
        label.setText("Taille : " + Math.round(textSp));
        SeekBar sb = new SeekBar(this);
        sb.setMax(46);
        sb.setProgress(Math.round(textSp) - 10);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) {
                    textSp = 10 + p;
                    label.setText("Taille : " + Math.round(textSp));
                    applyTextSize();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        box.addView(label);
        box.addView(sb);
        new AlertDialog.Builder(this)
                .setTitle("Taille du texte")
                .setView(box)
                .setPositiveButton("OK", null)
                .show();
    }

    private void showThemeDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Thème")
                .setSingleChoiceItems(THEME_NAMES, theme, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        theme = which;
                        applyTheme();
                        d.dismiss();
                    }
                })
                .show();
    }

    private void showEncodingDialog() {
        if (curUri == null) {
            toast("L'encodage ne peut être changé que pour un fichier ouvert depuis l'appareil.");
            return;
        }
        final String[] labels = {"Automatique", "UTF-8", "Windows-1256 (arabe)", "Windows-1252 (français)",
                "ISO-8859-1", "ISO-8859-6 (arabe)", "UTF-16 (petit-boutiste)"};
        final String[] names = {null, "UTF-8", "windows-1256", "windows-1252", "ISO-8859-1", "ISO-8859-6", "UTF-16LE"};
        new AlertDialog.Builder(this)
                .setTitle("Encodage (actuel : " + charset.name() + ")")
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        final Charset cs = names[which] == null ? null : TextCodec.forName(names[which]);
                        openUri(curUri, cs);
                    }
                })
                .show();
    }

    private void showInfo() {
        syncText();
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        String msg = "Nom : " + curName
                + "\nTaille : " + formatSize(fileBytes)
                + "\nEncodage : " + charset.name() + (bom ? " (avec BOM)" : "")
                + "\nFins de ligne : " + (crlf ? "Windows (CRLF)" : "Unix (LF)")
                + "\nLignes : " + lines
                + "\nCaractères : " + text.length();
        new AlertDialog.Builder(this).setTitle("Informations").setMessage(msg).setPositiveButton("OK", null).show();
    }

    private static String formatSize(long b) {
        if (b < 1024) {
            return b + " octets";
        }
        if (b < 1024L * 1024) {
            return String.format(Locale.FRANCE, "%.1f Ko", b / 1024.0);
        }
        return String.format(Locale.FRANCE, "%.1f Mo", b / (1024.0 * 1024.0));
    }
}
