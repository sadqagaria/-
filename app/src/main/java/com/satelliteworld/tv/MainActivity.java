
package com.satelliteworld.tv;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Typeface;
import android.content.res.Configuration;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.*;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final int BG=0xff091322, PANEL=0xff162438, ACCENT=0xff25dac7, MUTED=0xffaabbd1;
    private static final String TV="https://iptv-org.github.io/iptv/index.country.m3u";
    private static final String RADIO="https://raw.githubusercontent.com/eyalev/verified-radio-streams/main/m3u/all.m3u";
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ArrayList<Channel> catalog=new ArrayList<>();
    private final ArrayList<Channel> shown=new ArrayList<>();
    private ExoPlayer player;
    private PlayerView playerView;
    private LinearLayout root,tabsRow;
    private TextView status,count,playing;
    private EditText search;
    private ListView listView;
    private ChannelAdapter adapter;
    private SharedPreferences prefs;
    private String tab="مصر",digits="";
    private Channel current;
    private final Runnable numberAction=()->{
        int num; try {num=Integer.parseInt(digits);} catch(Exception e){num=-1;}
        digits="";
        if(num>0 && num<=shown.size()) play(shown.get(num-1));
    };

    static class Channel {
        final String name,url,group,country,source;
        final boolean radio;
        Channel(String n,String u,String g,String c,String s,boolean r){
            name=n;url=u;group=g;country=c;source=s;radio=r;
        }
        String id(){return Integer.toHexString((name+"|"+url).hashCode());}
    }

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        prefs=getSharedPreferences("receiver",MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        player=new ExoPlayer.Builder(this).build();
        player.addListener(new Player.Listener(){
            @Override public void onPlaybackStateChanged(int s){
                if(s==Player.STATE_READY) setStatus("البث يعمل الآن");
                if(s==Player.STATE_BUFFERING && current!=null) setStatus("جاري الاتصال بالبث...");
            }
            @Override public void onPlayerError(PlaybackException e){
                setStatus("تعذر تشغيل الرابط أو البث غير متاح في منطقتك");
            }
        });
        buildScreen();
        loadCatalog(false);
    }

    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String v,int size,int color){
        TextView t=new TextView(this);
        t.setText(v);t.setTextColor(color);t.setTextSize(size);t.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return t;
    }
    private TextView button(String title, Runnable action){
        TextView t=text(title,14,Color.WHITE);
        t.setPadding(dp(13),dp(9),dp(13),dp(9));
        t.setBackgroundColor(PANEL);
        t.setOnClickListener(v->action.run());
        return t;
    }
    private void setStatus(String s){runOnUiThread(()->{if(status!=null)status.setText(s);});}

    private void buildScreen(){
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);root.setPadding(dp(8),dp(5),dp(8),dp(5));
        setContentView(root);
        LinearLayout header=new LinearLayout(this);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title=text("SATELLITE WORLD TV",17,ACCENT);title.setTypeface(null,Typeface.BOLD);
        header.addView(title,new LinearLayout.LayoutParams(0,dp(46),1f));
        header.addView(button("استيراد",()->pickFile()));
        header.addView(button("تحديث",()->loadCatalog(true)));
        root.addView(header);
        int w=getResources().getConfiguration().screenWidthDp;
        playerView=new PlayerView(this);playerView.setPlayer(player);
        playerView.setBackgroundColor(Color.BLACK);
        root.addView(playerView,new LinearLayout.LayoutParams(-1,dp(w>680?300:194)));
        playing=text("اختر قناة لبدء المشاهدة",15,Color.WHITE);
        playing.setTypeface(null,Typeface.BOLD);playing.setPadding(dp(5),dp(6),dp(5),dp(3));
        root.addView(playing);
        status=text("جاري قراءة دليل القنوات...",12,MUTED);
        status.setMaxLines(2);status.setPadding(dp(5),0,dp(5),dp(6));
        root.addView(status);
        HorizontalScrollView hs=new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        tabsRow=new LinearLayout(this);tabsRow.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(tabsRow);root.addView(hs);
        search=new EditText(this);search.setSingleLine(true);search.setTextColor(Color.WHITE);
        search.setHintTextColor(MUTED);search.setHint("بحث عن قناة أو رقم...");
        search.setTextSize(14);search.setBackgroundColor(PANEL);
        search.setPadding(dp(12),dp(7),dp(12),dp(7));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,dp(47));sp.topMargin=dp(7);
        root.addView(search,sp);
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int a,int c,int d){}
            public void onTextChanged(CharSequence s,int start,int before,int count){filter();}
            public void afterTextChanged(Editable e){}
        });
        count=text("القنوات",12,MUTED);
        count.setPadding(dp(5),dp(6),dp(5),dp(6));root.addView(count);
        listView=new ListView(this);listView.setBackgroundColor(PANEL);
        listView.setDividerHeight(1);
        adapter=new ChannelAdapter();listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent,view,pos,id)->play(shown.get(pos)));
        root.addView(listView,new LinearLayout.LayoutParams(-1,0,1f));
        LinearLayout controls=new LinearLayout(this);
        controls.setGravity(android.view.Gravity.CENTER_VERTICAL);
        controls.addView(button("◀ السابق",()->changeChannel(-1)),new LinearLayout.LayoutParams(0,dp(47),1f));
        controls.addView(button("التالي ▶",()->changeChannel(1)),new LinearLayout.LayoutParams(0,dp(47),1f));
        root.addView(controls);
        makeTabs();
    }

    private void makeTabs(){
        tabsRow.removeAllViews();
        String[] tabs={"مصر","الخليج","العرب","أوروبا","إنجليزي","العالم","نايل سات","عرب سات","هوت بيرد","قائمتي","الراديو","المفضلة","الكل"};
        for(String s:tabs){
            TextView chip=button(s,()->{tab=s;makeTabs();filter();});
            chip.setTextColor(s.equals(tab)?ACCENT:Color.WHITE);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(39));
            p.setMargins(0,0,dp(5),dp(5));tabsRow.addView(chip,p);
        }
    }

    private void loadCatalog(boolean update){
        setStatus(update?"جاري تحديث القنوات والراديو من GitHub...":"جاري تحميل دليل القنوات...");
        executor.execute(()->{
            if(update||!new File(getFilesDir(),"tv.m3u").exists()) download(TV,"tv.m3u");
            if(update||!new File(getFilesDir(),"radio.m3u").exists()) download(RADIO,"radio.m3u");
            ArrayList<Channel> entries=new ArrayList<>();
            parseFile(entries,"tv.m3u",false,"GitHub");
            parseFile(entries,"radio.m3u",true,"GitHub");
            parseFile(entries,"my-list.m3u",false,"قائمتي");
            runOnUiThread(()->{
                catalog.clear();catalog.addAll(entries);
                filter();
                setStatus("تم فهرسة "+catalog.size()+" قناة ومحطة. تشغيل الروابط غير مضمون.");
            });
        });
    }

    private void download(String url,String file){
        File dst=new File(getFilesDir(),file), temp=new File(getFilesDir(),file+".part");
        try{
            HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
            c.setConnectTimeout(16000);c.setReadTimeout(40000);
            c.setRequestProperty("User-Agent","SatelliteWorldTV/0.1");
            try{
                if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode());
                try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(temp)){
                    byte[] buf=new byte[8192];int len;long total=0;
                    while((len=in.read(buf))>=0){
                        total+=len;if(total>30_000_000)throw new IOException("Playlist exceeds size limit");
                        out.write(buf,0,len);
                    }
                }
                if(temp.length()>100 && (dst.delete()||!dst.exists())) temp.renameTo(dst);
            }finally{c.disconnect();}
        }catch(Exception e){temp.delete();setStatus("تعذر تحديث مصدر، سيتم استخدام النسخة المحفوظة");}
    }

    private String attr(String s,String key){
        String pattern=key+"=\"";
        int a=s.indexOf(pattern);
        if(a<0)return "";
        a+=pattern.length();int z=s.indexOf('"',a);
        return z<0?"":s.substring(a,z);
    }
    private void parseFile(ArrayList<Channel> result,String file,boolean radio,String source){
        File f=new File(getFilesDir(),file);if(!f.exists())return;
        try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(f),StandardCharsets.UTF_8))){
            String line,inf="",grp="";
            while((line=r.readLine())!=null && result.size()<60000){
                line=line.trim();
                if(line.startsWith("#EXTINF:")){inf=line;grp="";continue;}
                if(line.startsWith("#EXTGRP:")){grp=line.substring(8).trim();continue;}
                if(!line.startsWith("https://")&&!line.startsWith("http://"))continue;
                String name=inf.contains(",")?inf.substring(inf.indexOf(',')+1).trim():"";
                if(name.isEmpty())name=attr(inf,"tvg-name");
                if(name.isEmpty())name="Channel "+(result.size()+1);
                String group=grp.isEmpty()?attr(inf,"group-title"):grp;
                if(group.isEmpty())group=radio?"Radio":"World";
                String country=attr(inf,"tvg-country");
                result.add(new Channel(name,line,group,country,source,radio));
                inf="";grp="";
            }
        }catch(Exception ignored){}
    }

    private boolean match(Channel c){
        if(tab.equals("المفضلة"))return prefs.getBoolean("f_"+c.id(),false);
        if(tab.equals("الراديو"))return c.radio;
        if(c.radio)return false;
        if(tab.equals("الكل")||tab.equals("العالم"))return true;
        if(tab.equals("قائمتي"))return c.source.equals("قائمتي");
        String g=(c.group+" "+c.country).toLowerCase(Locale.ROOT);
        switch(tab){
            case "مصر":return g.contains("egypt")||g.contains("مصر")||c.country.equalsIgnoreCase("EG");
            case "الخليج":return has(g,"saudi","emirates","kuwait","qatar","bahrain","oman","السعود","الامارات","الكويت","قطر");
            case "العرب":return has(g,"egypt","saudi","emirates","kuwait","qatar","bahrain","oman","iraq","jordan","lebanon","arab","algeria","morocco","tunisia","sudan");
            case "أوروبا":return has(g,"europe","france","germany","italy","spain","united kingdom","portugal","poland","netherlands","turkey");
            case "إنجليزي":return has(g,"united kingdom","united states","canada","australia","english","new zealand");
            case "نايل سات":return has(g,"nilesat","7°w","7w");
            case "عرب سات":return has(g,"arabsat","badr","26°e","26e");
            case "هوت بيرد":return has(g,"hot bird","hotbird","13°e","13e");
            default:return true;
        }
    }
    private boolean has(String hay,String... needles){
        for(String x:needles)if(hay.contains(x))return true;
        return false;
    }
    private void filter(){
        if(adapter==null)return;
        shown.clear();
        String q=search==null?"":search.getText().toString().trim().toLowerCase(Locale.ROOT);
        for(Channel c:catalog)
            if(match(c)&&(q.isEmpty()||c.name.toLowerCase(Locale.ROOT).contains(q)||
                c.group.toLowerCase(Locale.ROOT).contains(q)))shown.add(c);
        adapter.notifyDataSetChanged();
        count.setText(tab+"  •  "+shown.size()+" نتيجة من "+catalog.size());
    }
    private void play(Channel c){
        current=c;
        playing.setText(c.name+"   |   "+c.group);
        setStatus("جارٍ الاتصال بالبث...");
        prefs.edit().putString("last",c.url).apply();
        try{
            player.setMediaItem(MediaItem.fromUri(Uri.parse(c.url)));
            player.prepare();player.play();
        }catch(Exception e){setStatus("الرابط غير قابل للتشغيل");}
    }
    private void changeChannel(int direction){
        if(shown.isEmpty())return;
        int index=current==null?-1:shown.indexOf(current);
        int next=(index+direction+shown.size())%shown.size();
        play(shown.get(next));
    }

    class ChannelAdapter extends android.widget.BaseAdapter{
        public int getCount(){return shown.size();}
        public Object getItem(int p){return shown.get(p);}
        public long getItemId(int p){return p;}
        public View getView(int p,View old,ViewGroup parent){
            Channel c=shown.get(p);
            LinearLayout row=new LinearLayout(MainActivity.this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8),dp(5),dp(8),dp(5));
            row.setBackgroundColor(c==current?0xff25534d:PANEL);
            TextView n=text(""+(p+1),12,ACCENT);
            row.addView(n,new LinearLayout.LayoutParams(dp(43),dp(49)));
            LinearLayout labels=new LinearLayout(MainActivity.this);labels.setOrientation(LinearLayout.VERTICAL);
            TextView name=text(c.name,14,Color.WHITE);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView sub=text(c.group+"  •  "+(c.radio?"RADIO":"TV"),11,MUTED);sub.setSingleLine(true);
            labels.addView(name);labels.addView(sub);
            row.addView(labels,new LinearLayout.LayoutParams(0,-2,1f));
            TextView fav=button(prefs.getBoolean("f_"+c.id(),false)?"★":"☆",()->{
                boolean next=!prefs.getBoolean("f_"+c.id(),false);
                prefs.edit().putBoolean("f_"+c.id(),next).apply();filter();
            });
            fav.setTextColor(ACCENT);
            row.addView(fav,new LinearLayout.LayoutParams(dp(45),dp(45)));
            return row;
        }
    }
    private void pickFile(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i,41);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==41&&result==RESULT_OK&&data!=null&&data.getData()!=null){
            Uri uri=data.getData();setStatus("جاري استيراد M3U...");
            executor.execute(()->{
                try(InputStream in=getContentResolver().openInputStream(uri);
                    OutputStream out=new FileOutputStream(new File(getFilesDir(),"my-list.m3u"))){
                    byte[] buf=new byte[8192];int n;long total=0;
                    while((n=in.read(buf))!=-1){
                        total+=n;if(total>25_000_000)throw new IOException("File too large");
                        out.write(buf,0,n);
                    }
                    runOnUiThread(()->{tab="قائمتي";makeTabs();loadCatalog(false);});
                }catch(Exception e){setStatus("فشل استيراد القائمة");}
            });
        }
    }
    @Override public boolean onKeyDown(int code,KeyEvent event){
        if(code==KeyEvent.KEYCODE_CHANNEL_UP){changeChannel(1);return true;}
        if(code==KeyEvent.KEYCODE_CHANNEL_DOWN){changeChannel(-1);return true;}
        if(code>=KeyEvent.KEYCODE_0&&code<=KeyEvent.KEYCODE_9){
            digits+=Integer.toString(code-KeyEvent.KEYCODE_0);
            setStatus("رقم القناة: "+digits);
            handler.removeCallbacks(numberAction);handler.postDelayed(numberAction,1200);
            return true;
        }
        return super.onKeyDown(code,event);
    }
    @Override public void onDestroy(){
        handler.removeCallbacksAndMessages(null);player.release();executor.shutdownNow();
        super.onDestroy();
    }
}
