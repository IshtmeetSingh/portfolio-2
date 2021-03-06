package name.abuchen.portfolio.datatransfer;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import name.abuchen.portfolio.Messages;
import name.abuchen.portfolio.datatransfer.Extractor.Item;
import name.abuchen.portfolio.datatransfer.Extractor.SecurityItem;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;

public class SecurityCache
{
    private static final Security DUPLICATE_SECURITY_MARKER = new Security();

    private static final List<String> MESSAGES = Arrays.asList(Messages.MsgErrorDuplicateISIN,
                    Messages.MsgErrorDuplicateTicker, Messages.MsgErrorDuplicateWKN, Messages.MsgErrorDuplicateName);

    private final Client client;

    private final List<Map<String, Security>> localMaps = new ArrayList<>();

    public SecurityCache(Client client)
    {
        this.client = client;

        this.localMaps.add(client.getSecurities().stream().filter(s -> s.getIsin() != null && !s.getIsin().isEmpty())
                        .collect(Collectors.toMap(Security::getIsin, s -> s, (l, r) -> DUPLICATE_SECURITY_MARKER)));

        this.localMaps.add(client.getSecurities().stream()
                        .filter(s -> s.getTickerSymbol() != null && !s.getTickerSymbol().isEmpty()) //
                        .collect(Collectors.toMap(Security::getTickerSymbol, s -> s,
                                        (l, r) -> DUPLICATE_SECURITY_MARKER)));

        this.localMaps.add(client.getSecurities().stream().filter(s -> s.getWkn() != null && !s.getWkn().isEmpty())
                        .collect(Collectors.toMap(Security::getWkn, s -> s, (l, r) -> DUPLICATE_SECURITY_MARKER)));

        this.localMaps.add(client.getSecurities().stream().filter(s -> s.getName() != null && !s.getName().isEmpty())
                        .collect(Collectors.toMap(Security::getName, s -> s, (l, r) -> DUPLICATE_SECURITY_MARKER)));

    }

    public Security lookup(String isin, String tickerSymbol, String wkn, String name,
                    Supplier<Security> creationFunction)
    {
        List<String> attributes = Arrays.asList(isin, tickerSymbol, wkn, name);

        // first: check the identifying attributes (ISIN, Ticker, WKN)
        for (int ii = 0; ii < 3; ii++)
        {
            String attribute = attributes.get(ii);

            Security security = localMaps.get(ii).get(attribute);
            if (security == DUPLICATE_SECURITY_MARKER)
                throw new IllegalArgumentException(MessageFormat.format(MESSAGES.get(ii), attribute));
            if (security != null)
                return security;
        }

        // second: check the name. But: even if the name matches, we also must
        // check that the identifying attributes do not differ. Why? Investment
        // instruments could have the same name but different ISINs.

        Security security = lookupSecurityByName(isin, tickerSymbol, wkn, name);
        if (security != null)
            return security;


        security = creationFunction.get();
        security.setIsin(isin);
        security.setWkn(wkn);
        security.setTickerSymbol(tickerSymbol);
        security.setName(name);

        for (int ii = 0; ii < localMaps.size(); ii++)
        {
            String attribute = attributes.get(ii);
            if (attribute != null)
                localMaps.get(ii).put(attribute, security);
        }

        return security;
    }

    private Security lookupSecurityByName(String isin, String tickerSymbol, String wkn, String name)
    {
        Security security = localMaps.get(3).get(name);

        // allow imports by duplicate name
        if (security == null || security == DUPLICATE_SECURITY_MARKER)
            return null;

        if (doNotMatchIfGiven(isin, security.getIsin()))
            return null;
        if (doNotMatchIfGiven(tickerSymbol, security.getTickerSymbol()))
            return null;
        if (doNotMatchIfGiven(wkn, security.getWkn()))
            return null;
        return security;
    }

    private boolean doNotMatchIfGiven(String attribute1, String attribute2)
    {
        return attribute1 != null && attribute2 != null && !attribute1.equalsIgnoreCase(attribute2);
    }

    /**
     * Returns a list of {@link SecurityItem} that are implicitly created when
     * extracting transactions. Do not add all newly created securities as they
     * might be created out of erroneous transactions.
     */
    public Collection<Item> createMissingSecurityItems(List<Item> items)
    {
        List<Item> answer = new ArrayList<>();

        Set<Security> available = new HashSet<>();
        available.addAll(client.getSecurities());
        items.stream().filter(i -> i instanceof SecurityItem).map(Item::getSecurity).forEach(available::add);

        for (Item item : items)
        {
            if (item instanceof SecurityItem || item.getSecurity() == null)
                continue;

            if (!available.contains(item.getSecurity()))
            {
                answer.add(new SecurityItem(item.getSecurity()));
                available.add(item.getSecurity());
            }
        }

        return answer;
    }
}
