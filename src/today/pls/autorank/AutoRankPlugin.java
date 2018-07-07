package today.pls.autorank;

import codecrafter47.bungeetablistplus.BungeeTabListPlus;
import codecrafter47.bungeetablistplus.api.bungee.BungeeTabListPlusAPI;
import me.lucko.luckperms.LuckPerms;
import me.lucko.luckperms.api.DataMutateResult;
import me.lucko.luckperms.api.Group;
import me.lucko.luckperms.api.LuckPermsApi;
import me.lucko.luckperms.api.User;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.command.ConsoleCommandSender;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import net.md_5.bungee.event.EventHandler;

import javax.xml.soap.Text;
import java.io.File;
import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class AutoRankPlugin extends Plugin implements Listener {

    private List<Role> roles = new ArrayList<>();

    private HashMap<UUID,Long> timePlayed = new HashMap<>();
    private HashMap<UUID,Long> joinTime = new HashMap<>();

    private String sqlUser, sqlPass, sqlHost, sqlDB, sqlPrefix;

    private Connection connection;

    private LuckPermsApi lpapi;

    @Override
    public void onEnable() {
        Optional<LuckPermsApi> lp = LuckPerms.getApiSafe();
        if(!lp.isPresent()){
            getLogger().severe("Could not load LuckPerms. Disabling");
            return;
        }else{
            lpapi = lp.get();
        }

        if(!loadConfig()){
            return;
        }

        if(!loadTimes()){
            return;
        }

        getProxy().getScheduler().schedule(this, new PlayTimeTicker(), 2L,2L, TimeUnit.SECONDS);
        getProxy().getPluginManager().registerListener(this, this);

        getProxy().getPluginManager().registerCommand(this, new Command("plsarreload") {
            @Override
            public void execute(CommandSender commandSender, String[] strings) {
                if(commandSender instanceof ConsoleCommandSender || commandSender.hasPermission("plsar.reload")){
                    TextComponent cp = new TextComponent();

                    if(loadConfig()){
                        cp.setText("§4[§bPLS§4] §2Successfully reloaded config.");
                    }else{
                        cp.setText("§4[§bPLS§4] §4Failed to reload config.");
                        onDisable();
                    }
                }
            }
        });

        getProxy().getPluginManager().registerCommand(this, new Command("timeplayed") {
            @Override
            public void execute(CommandSender commandSender, String[] strings) {
                if(commandSender instanceof ProxiedPlayer){
                    ProxiedPlayer pp = (ProxiedPlayer) commandSender;
                    long tp = getTimePlayed(pp.getUniqueId());

                    long s = tp/1000L;
                    long d = (s/86400L);
                    long h = ((s%86400L)/3600L);
                    long m = ((s%3600L)/60L);
                    s = (s%60L);

                    String tps = (d>0?d + "d ":"")
                            +  (h>0?h + "h ":"")
                            +  (m>0||h>0?m+"m ":"")
                            +  (s>0||m>0||h>0?s+"s":"");


                    TextComponent cp = new TextComponent();

                    cp.setText("§4[§bPLS§4] §cYou have played for: §b" + tps + "§c.");
                    Role nr = getNextRole(pp.getUniqueId(),tp);

                    if(nr != null){
                        long ttr = getTimeLeft(pp.getUniqueId(),tp);

                        s = ttr;
                        d = (s/86400L);
                        h = ((s%86400L)/3600L);
                        m = ((s%3600L)/60L);
                        s = (s%60L);

                        String ttrs = (d>0?d + "d ":"")
                                +  (h>0?h + "h ":"")
                                +  (m>0||h>0?m+"m ":"")
                                +  (s>0||m>0||h>0?s+"s":"");

                        cp.addExtra("\n§4[§bPLS§4] §cThe next role is §a" + nr.roleName);
                        cp.addExtra("\n§4[§bPLS§4] §c  In... §b" + ttrs);
                    }

                    pp.sendMessage(cp);
                }
            }
        });
        BungeeTabListPlusAPI.registerVariable(this,new Variables.NextRankVariable(this));
        BungeeTabListPlusAPI.registerVariable(this,new Variables.TimePlayedVariable(this));
        BungeeTabListPlusAPI.registerVariable(this,new Variables.TimeRemainingVariable(this));
    }

    private boolean loadConfig() {
        try {
            Configuration config = ConfigurationProvider.getProvider(YamlConfiguration.class).load(new File(getDataFolder(), "config.yml"));
            List<LinkedHashMap<String,Object>> rlist = (List<LinkedHashMap<String, Object>>) config.getList("roles");

            for (LinkedHashMap<String,Object> rd : rlist) {
                Role r = new Role();
                r.roleName = (String) rd.get("roleName");
                lpapi.getGroupManager().loadAllGroups();
                if(lpapi.getGroupManager().getGroup(r.roleName) == null){
                    getLogger().severe("INVALID GROUP "+r.roleName+" IN CONFIG, DISABLING PLUGIN.");
                    return false;
                }
                r.requireRole = (String) rd.get("requireRole");
                if(lpapi.getGroupManager().getGroup(r.requireRole) == null){
                    getLogger().severe("INVALID GROUP "+r.requireRole+" IN CONFIG, DISABLING PLUGIN.");
                    return false;
                }

                r.timeToRole = (int) rd.get("timeToRole");
                r.completionText = (String) rd.get("completionText");

                roles.add(r);
            }

            roles.sort((r1, r2) -> (int)(r1.timeToRole - r2.timeToRole));

            sqlUser = config.getString("mysql.username");
            sqlPass = config.getString("mysql.password");
            sqlDB = config.getString("mysql.database");
            sqlHost = config.getString("mysql.host");
            sqlPrefix = config.getString("mysql.prefix");
            String conURL = "jdbc:mysql://"+sqlHost+"/"+sqlDB;

            try { //We use a try catch to avoid errors, hopefully we don't get any.
                Class.forName("com.mysql.jdbc.Driver"); //this accesses Driver in jdbc.
                connection = DriverManager.getConnection(conURL,sqlUser,sqlPass);
                connection.setAutoCommit(true);
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
                getLogger().severe("jdbc driver unavailable!");
                return false;
            } catch (SQLException e) {
                e.printStackTrace();
            }

        } catch (IOException e) {
            getLogger().severe("COULD NOT LOAD CONFIG, DISABLING PLUGIN.");
            return false;
        }

        return true;
    }

    public class Role{
        String roleName;
        String requireRole;
        int timeToRole;
        String completionText;
    }

    @Override
    public void onDisable() {
        saveTimes();

        getProxy().getScheduler().cancel(this);
        getProxy().getPluginManager().unregisterCommands(this);
        getProxy().getPluginManager().unregisterListeners(this);

        joinTime.clear();
        timePlayed.clear();
        roles.clear();

        try{
            connection.close();
        }catch(Exception e){
            //We tried?
        }
    }

    @EventHandler
    public void onJoin(PostLoginEvent le){
        UUID id = le.getPlayer().getUniqueId();
        joinTime.put(id,System.currentTimeMillis());
    }

    @EventHandler
    public void onLeave(PlayerDisconnectEvent de){
        UUID id = de.getPlayer().getUniqueId();
        long playtime = getTimePlayed(id);

        try {
            PreparedStatement s = connection.prepareStatement("INSERT INTO `"+sqlPrefix+"TimePlayed` VALUES(?,?) ON DUPLICATE KEY UPDATE `timePlayed` = ?");

            if(playtime != 0 && playtime != timePlayed.get(id)){
                s.setString(1, id.toString());
                s.setLong(2, playtime);
                s.setLong(3, playtime);
                s.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        timePlayed.remove(id);
    }

    /**
     * Set every player's jointime to now, and create the table if it doesnt exist
     * @return whether func succeeded or not
     */
    private boolean loadTimes() {
        for (ProxiedPlayer pp:getProxy().getPlayers()) {
            joinTime.put(pp.getUniqueId(),System.currentTimeMillis());
        }

        try {
            PreparedStatement s = connection.prepareStatement("CREATE TABLE IF NOT EXISTS `"+sqlPrefix+"TimePlayed`(`uuid` VARCHAR(37) NOT NULL, `timePlayed` BIGINT NOT NULL, PRIMARY KEY (`uuid`));");
            s.executeUpdate();
        } catch (SQLException e) {
            getLogger().severe("Could not create table (ERROR " + e.getErrorCode() + "): " + e.getMessage());
            return false;
        }

        return true;
    }

    private void saveTimes() {
        try {
            if(connection.isClosed()){
                getLogger().severe("COULD NOT SAVE PLAYERDATA, CONNECTION IS CLOSED.");
                return;
            }
            PreparedStatement s = connection.prepareStatement("INSERT INTO `"+sqlPrefix+"TimePlayed` VALUES(?,?) ON DUPLICATE KEY UPDATE `timePlayed` = ?");

            for ( ProxiedPlayer pp: getProxy().getPlayers()) {
                UUID id = pp.getUniqueId();
                long tp = getTimePlayed(id);
                if(tp != 0 && tp != timePlayed.get(id)){
                    s.setString(1, id.toString());
                    s.setLong(2, tp);
                    s.setLong(3, tp);
                    s.addBatch();
                }
                s.executeUpdate();
            }
            getLogger().info("Successfully saved playtimes. Goodbye.");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * Load timeplayed from sql, cache it, get lit, get bit, get woke, get good. add it to current session time, ezpz.
     * @param id of player
     * @return time played in ms
     */
    long getTimePlayed(UUID id){
        if(!timePlayed.containsKey(id)){
            try {
                PreparedStatement s = connection.prepareStatement("SELECT * FROM `"+sqlPrefix+"TimePlayed` WHERE `uuid` = ?");
                s.setString(1, id.toString());
                ResultSet r = s.executeQuery();
                if(!r.next()){
                    timePlayed.put(id,0L);
                }else{
                    timePlayed.put(id,r.getLong("timePlayed"));
                }
            } catch (SQLException e) {
                getLogger().warning("FAILED TO READ THE DATA FOR UUID="+id);
                e.printStackTrace();
            }
        }

        if(!joinTime.containsKey(id)){
            joinTime.put(id,System.currentTimeMillis());
        }

        return timePlayed.get(id) + (System.currentTimeMillis() - joinTime.get(id));
    }

    Role getNextRole(UUID id){
        return getNextRole(id,getTimePlayed(id));
    }


    Role getNextRole(UUID id, long tp) {
        Optional<User> u = lpapi.getUserManager().getUserOpt(id);

        if(!u.isPresent()) return null;

        //already sorted least to greatest
        for( Role r : roles ){
            if(r.timeToRole > (tp/1000)){
                if(!u.get().hasPermission(lpapi.getNodeFactory().makeGroupNode(r.requireRole).build()).asBoolean()) continue;

                return r;
            }
        }

        return null;
    }

    long getTimeLeft(UUID id) {
        long tp = getTimePlayed(id);
        return getTimeLeft(id,tp);
    }
    long getTimeLeft(UUID id, long tp){
        Role r = getNextRole(id,tp);

        if(r==null) return -1;

        return r.timeToRole - (tp/1000);
    }

    public class PlayTimeTicker extends Thread{
        public void run(){
            try {
                for (ProxiedPlayer pp : getProxy().getPlayers()) {
                    UUID id = pp.getUniqueId();
                    long tp = getTimePlayed(id) / 1000; //ms to s

                    Optional<User> uo = lpapi.getUserSafe(id);
                    User u;
                    u = uo.orElseGet(() -> lpapi.getUserManager().loadUser(id).join());

                    for (Role r : roles) {
                        if (r.timeToRole <= tp) {
                            giveUserRole(pp, u,r.roleName,r.requireRole,r.completionText);
                        }
                    }
                }
            }catch(Exception e){
                e.printStackTrace();
            }
        }

        boolean giveUserRole(ProxiedPlayer pp, User u, String r, String req, String comptext){
            if (u.hasPermission(lpapi.getNodeFactory().makeGroupNode(req).build()).asBoolean()){
                u.refreshCachedData();
                u.clearParents();
                DataMutateResult dmr = u.setPermission(lpapi.getNodeFactory().makeGroupNode(r).build());
                if(dmr.wasFailure()){
                    getLogger().severe("FAILED TO PROMOTE "+u.getUuid()+" TO "+r);
                }else{
                    TextComponent tc = new TextComponent();
                    tc.setText(comptext.replace("&","§").replace("{user}",pp.getDisplayName()).replace("{role}",r));
                    pp.sendMessage(ChatMessageType.CHAT, tc);
                }
                lpapi.getUserManager().saveUser(u);
                return true;
            }
            return false;
        }
    }
}
