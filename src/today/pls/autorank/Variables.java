package today.pls.autorank;

import codecrafter47.bungeetablistplus.api.bungee.Variable;
import net.md_5.bungee.api.connection.ProxiedPlayer;

public class Variables {
    public static class TimePlayedVariable extends Variable {

        AutoRankPlugin arp;

        public TimePlayedVariable(AutoRankPlugin _arp) {
            super("plstimeplayed");
            arp = _arp;
        }

        @Override
        public String getReplacement(ProxiedPlayer proxiedPlayer) {
            long s = arp.getTimePlayed(proxiedPlayer.getUniqueId())/1000;

            long d = (s/86400L);
            long h = ((s%86400L)/3600L);
            long m = ((s%3600L)/60L);
            s = (s%60L);

            String t = (d>0?d + "d ":"")
                    +  (h>0?h + "h ":"")
                    +  (m>0||h>0?m+"m ":"")
                    +  (s>0||m>0||h>0?s+"s":"");

            return t;
        }
    }
    public static class TimeRemainingVariable extends Variable {

        AutoRankPlugin arp;

        public TimeRemainingVariable(AutoRankPlugin _arp) {
            super("plstimeremaining");
            arp = _arp;
        }

        @Override
        public String getReplacement(ProxiedPlayer proxiedPlayer) {
            long s = arp.getTimeLeft(proxiedPlayer.getUniqueId());

            if(s<=0) return "";

            long d = (s/86400L);
            long h = ((s%86400L)/3600L);
            long m = ((s%3600L)/60L);
            s = (s%60L);

            String t = (d>0?d + "d ":"")
                    +  (h>0?h + "h ":"")
                    +  (m>0||h>0?m+"m ":"")
                    +  (s>0||m>0||h>0?s+"s":"");

            return t;
        }
    }
    public static class NextRankVariable extends Variable {

        AutoRankPlugin arp;

        public NextRankVariable(AutoRankPlugin _arp) {
            super("plsnextrank");
            arp = _arp;
        }

        @Override
        public String getReplacement(ProxiedPlayer proxiedPlayer) {
            AutoRankPlugin.Role r = arp.getNextRole(proxiedPlayer.getUniqueId());
            if(r == null) return "";
            return r.roleName;
        }
    }
}
