package name.abuchen.portfolio.util;

import java.text.MessageFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import name.abuchen.portfolio.Messages;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Security.AssetClass;
import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.online.QuoteFeed;
import name.abuchen.portfolio.util.CSVImporter.AmountField;
import name.abuchen.portfolio.util.CSVImporter.Column;
import name.abuchen.portfolio.util.CSVImporter.DateField;
import name.abuchen.portfolio.util.CSVImporter.EnumField;
import name.abuchen.portfolio.util.CSVImporter.Field;
import name.abuchen.portfolio.util.CSVImporter.FieldFormat;

public abstract class CSVImportDefinition
{
    private String label;
    private List<Field> fields;

    /* package */CSVImportDefinition(String label)
    {
        this.label = label;
        this.fields = new ArrayList<Field>();
    }

    public List<Field> getFields()
    {
        return fields;
    }

    @Override
    public String toString()
    {
        return label;
    }

    public abstract List<?> getTargets(Client client);

    /* package */abstract void build(Client client, Object target, String[] rawValues, Map<String, Column> field2column)
                    throws ParseException;

    protected Integer convertAmount(String name, String[] rawValues, Map<String, Column> field2column)
                    throws ParseException
    {
        String value = getTextValue(name, rawValues, field2column);
        if (value == null)
            return null;

        Number num = (Number) field2column.get(name).getFormat().getFormat().parseObject(value);
        return Integer.valueOf((int) Math.round(num.doubleValue() * 100d));
    }

    protected Integer convertShares(String name, String[] rawValues, Map<String, Column> field2column)
                    throws ParseException
    {
        String value = getTextValue(name, rawValues, field2column);
        if (value == null)
            return null;

        Number num = (Number) field2column.get(name).getFormat().getFormat().parseObject(value);
        return (int) Math.round(num.doubleValue());
    }

    protected Date convertDate(String name, String[] rawValues, Map<String, Column> field2column) throws ParseException
    {
        String value = getTextValue(name, rawValues, field2column);
        if (value == null)
            return null;
        return (Date) field2column.get(name).getFormat().getFormat().parseObject(value);
    }

    @SuppressWarnings("unchecked")
    protected <E extends Enum<E>> E convertEnum(String name, Class<E> type, String[] rawValues,
                    Map<String, Column> field2column) throws ParseException
    {
        String value = getTextValue(name, rawValues, field2column);
        if (value == null)
            return null;
        FieldFormat ff = field2column.get(name).getFormat();

        if (ff != null && ff.getFormat() != null)
            return (E) ff.getFormat().parseObject(value);
        else
            return Enum.valueOf(type, value);
    }

    protected String getTextValue(String name, String[] rawValues, Map<String, Column> field2column)
    {
        Column column = field2column.get(name);
        int columnIndex = column.getColumnIndex();

        if (columnIndex < 0 || columnIndex >= rawValues.length)
            return null;

        String value = rawValues[columnIndex];
        return value != null && value.trim().length() == 0 ? null : value;
    }

    protected Security lookupOrCreateSecurity(Client client, String isin)
    {
        Security security = null;
        for (Security s : client.getSecurities())
        {
            if (isin.equals(s.getIsin()))
            {
                security = s;
                break;
            }
        }
        if (security == null)
        {
            security = new Security(MessageFormat.format(Messages.CSVImportedSecurityLabel, isin), isin, null,
                            AssetClass.EQUITY, QuoteFeed.MANUAL);
            client.addSecurity(security);
        }
        return security;
    }

    //
    // implementations
    //

    /* package */static class AccountTransactionDef extends CSVImportDefinition
    {
        /* package */AccountTransactionDef()
        {
            super(Messages.CSVDefAccountTransactions);

            List<Field> fields = getFields();
            fields.add(new DateField(Messages.CSVColumn_Date));
            fields.add(new Field(Messages.CSVColumn_ISIN));
            fields.add(new AmountField(Messages.CSVColumn_Value));
            fields.add(new EnumField<AccountTransaction.Type>(Messages.CSVColumn_Type, AccountTransaction.Type.class));
        }

        @Override
        public List<?> getTargets(Client client)
        {
            return client.getAccounts();
        }

        @Override
        void build(Client client, Object target, String[] rawValues, Map<String, Column> field2column)
                        throws ParseException
        {
            if (!(target instanceof Account))
                throw new IllegalArgumentException();

            Account account = (Account) target;

            Date date = convertDate(Messages.CSVColumn_Date, rawValues, field2column);
            if (date == null)
                throw new ParseException(MessageFormat.format(Messages.CSVImportMissingField, Messages.CSVColumn_Date),
                                0);

            Integer amount = convertAmount(Messages.CSVColumn_Value, rawValues, field2column);
            if (amount == null)
                throw new ParseException(
                                MessageFormat.format(Messages.CSVImportMissingField, Messages.CSVColumn_Value), 0);

            AccountTransaction.Type type = convertEnum(Messages.CSVColumn_Type, AccountTransaction.Type.class,
                            rawValues, field2column);

            AccountTransaction transaction = new AccountTransaction();
            transaction.setDate(date);
            transaction.setAmount(Math.abs(amount));
            String isin = getTextValue(Messages.CSVColumn_ISIN, rawValues, field2column);
            if (isin != null)
            {
                Security security = lookupOrCreateSecurity(client, isin);
                transaction.setSecurity(security);
            }

            if (type != null)
                transaction.setType(type);
            else if (transaction.getSecurity() != null)
                transaction.setType(amount < 0 ? AccountTransaction.Type.FEES : AccountTransaction.Type.DIVIDENDS);
            else
                transaction.setType(amount < 0 ? AccountTransaction.Type.REMOVAL : AccountTransaction.Type.DEPOSIT);

            account.addTransaction(transaction);
        }

    }

    /* package */static class PortfolioTransactionDef extends CSVImportDefinition
    {
        /* package */PortfolioTransactionDef()
        {
            super(Messages.CSVDefPortfolioTransactions);

            List<Field> fields = getFields();
            fields.add(new DateField(Messages.CSVColumn_Date));
            fields.add(new Field(Messages.CSVColumn_ISIN));
            fields.add(new AmountField(Messages.CSVColumn_Value));
            fields.add(new AmountField(Messages.CSVColumn_Fees));
            fields.add(new AmountField(Messages.CSVColumn_Shares));
            fields.add(new EnumField<PortfolioTransaction.Type>(Messages.CSVColumn_Type,
                            PortfolioTransaction.Type.class));
        }

        @Override
        public List<?> getTargets(Client client)
        {
            return client.getPortfolios();
        }

        @Override
        void build(Client client, Object target, String[] rawValues, Map<String, Column> field2column)
                        throws ParseException
        {
            if (!(target instanceof Portfolio))
                throw new IllegalArgumentException();

            Portfolio portfolio = (Portfolio) target;

            Date date = convertDate(Messages.CSVColumn_Date, rawValues, field2column);
            if (date == null)
                throw new ParseException(MessageFormat.format(Messages.CSVImportMissingField, Messages.CSVColumn_Date),
                                0);

            Integer amount = convertAmount(Messages.CSVColumn_Value, rawValues, field2column);
            if (amount == null)
                throw new ParseException(
                                MessageFormat.format(Messages.CSVImportMissingField, Messages.CSVColumn_Value), 0);

            Integer fees = convertAmount(Messages.CSVColumn_Fees, rawValues, field2column);
            if (fees == null)
                fees = Integer.valueOf(0);

            String isin = getTextValue(Messages.CSVColumn_ISIN, rawValues, field2column);
            if (isin == null)
                throw new ParseException(MessageFormat.format(Messages.CSVImportMissingField, Messages.CSVColumn_ISIN),
                                0);

            Integer shares = convertShares(Messages.CSVColumn_Shares, rawValues, field2column);
            if (shares == null)
                throw new ParseException(
                                MessageFormat.format(Messages.CSVImportMissingField, Messages.CSVColumn_Shares), 0);

            PortfolioTransaction.Type type = convertEnum(Messages.CSVColumn_Type, PortfolioTransaction.Type.class,
                            rawValues, field2column);

            PortfolioTransaction transaction = new PortfolioTransaction();
            transaction.setDate(date);
            transaction.setAmount(Math.abs(amount));
            transaction.setSecurity(lookupOrCreateSecurity(client, isin));
            transaction.setShares(Math.abs(shares));
            transaction.setFees(Math.abs(fees));

            if (type != null)
                transaction.setType(type);
            else
                transaction.setType(amount < 0 ? PortfolioTransaction.Type.BUY : PortfolioTransaction.Type.SELL);

            portfolio.addTransaction(transaction);
        }
    }

    /* package */static class SecurityPriceDef extends CSVImportDefinition
    {
        /* package */SecurityPriceDef()
        {
            super(Messages.CSVDefHistoricalQuotes);

            List<Field> fields = getFields();
            fields.add(new DateField(Messages.CSVColumn_Date));
            fields.add(new AmountField(Messages.CSVColumn_Quote));
        }

        @Override
        public List<?> getTargets(Client client)
        {
            return client.getSecurities();
        }

        @Override
        void build(Client client, Object target, String[] rawValues, Map<String, Column> field2column)
                        throws ParseException
        {
            if (!(target instanceof Security))
                throw new IllegalArgumentException();

            Security security = (Security) target;

            Date date = convertDate(Messages.CSVColumn_Date, rawValues, field2column);
            if (date == null)
                throw new ParseException(MessageFormat.format(Messages.CSVImportMissingField, Messages.CSVColumn_Date),
                                0);

            Integer amount = convertAmount(Messages.CSVColumn_Quote, rawValues, field2column);
            if (amount == null)
                throw new ParseException(
                                MessageFormat.format(Messages.CSVImportMissingField, Messages.CSVColumn_Quote), 0);

            security.addPrice(new SecurityPrice(date, Math.abs(amount)));
        }
    }

}