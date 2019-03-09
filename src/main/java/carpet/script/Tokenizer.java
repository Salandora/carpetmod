package carpet.script;

import java.util.Iterator;

/**
 * Expression tokenizer that allows to iterate over a {@link String}
 * expression token by token. Blank characters will be skipped.
 */
public class Tokenizer implements Iterator<Tokenizer.Token>
{

    /** What character to use for decimal separators. */
    private static final char decimalSeparator = '.';
    /** What character to use for minus sign (negative values). */
    private static final char minusSign = '-';
    /** Actual position in expression string. */
    private int pos = 0;
    private int lineno = 0;
    private int linepos = 0;


    /** The original input expression. */
    private String input;
    /** The previous token or <code>null</code> if none. */
    private Token previousToken;

    private Expression expression;

    Tokenizer(Expression expr, String input)
    {
        this.input = input;
        this.expression = expr;
    }

    @Override
    public boolean hasNext()
    {
        return (pos < input.length());
    }

    /**
     * Peek at the next character, without advancing the iterator.
     *
     * @return The next character or character 0, if at end of string.
     */
    private char peekNextChar()
    {
        return (pos < (input.length() - 1)) ? input.charAt(pos + 1) : 0;
    }

    private boolean isHexDigit(char ch)
    {
        return ch == 'x' || ch == 'X' || (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f')
                || (ch >= 'A' && ch <= 'F');
    }

    @Override
    public Token next()
    {
        Token token = new Token();

        if (pos >= input.length())
        {
            return previousToken = null;
        }
        char ch = input.charAt(pos);
        while (Character.isWhitespace(ch) && pos < input.length())
        {
            linepos++;
            if (ch=='\n')
            {
                lineno++;
                linepos = 0;
            }
            ch = input.charAt(++pos);
        }
        token.pos = pos;
        token.lineno = lineno;
        token.linepos = linepos;

        boolean isHex = false;

        if (Character.isDigit(ch) || (ch == decimalSeparator && Character.isDigit(peekNextChar())))
        {
            if (ch == '0' && (peekNextChar() == 'x' || peekNextChar() == 'X'))
                isHex = true;
            while ((isHex
                    && isHexDigit(
                    ch))
                    || (Character.isDigit(ch) || ch == decimalSeparator || ch == 'e' || ch == 'E'
                    || (ch == minusSign && token.length() > 0
                    && ('e' == token.charAt(token.length() - 1)
                    || 'E' == token.charAt(token.length() - 1)))
                    || (ch == '+' && token.length() > 0
                    && ('e' == token.charAt(token.length() - 1)
                    || 'E' == token.charAt(token.length() - 1))))
                    && (pos < input.length()))
            {
                token.append(input.charAt(pos++));
                linepos++;
                ch = pos == input.length() ? 0 : input.charAt(pos);
            }
            token.type = isHex ? Token.TokenType.HEX_LITERAL : Token.TokenType.LITERAL;
        }
        else if (ch == '\'')
        {
            pos++;
            linepos++;
            if (previousToken == null || previousToken.type != Token.TokenType.STRINGPARAM)
            {
                ch = input.charAt(pos);
                while (ch != '\'')
                {
                    token.append(input.charAt(pos++));
                    linepos++;
                    ch = pos == input.length() ? 0 : input.charAt(pos);
                }
                pos++;
                linepos++;
                token.type = Token.TokenType.STRINGPARAM;
            }
            else
            {
                return next();
            }
        }
        else if (Character.isLetter(ch) || "_".indexOf(ch) >= 0)
        {
            while ((Character.isLetter(ch) || Character.isDigit(ch) || "_".indexOf(ch) >= 0
                    || token.length() == 0 && "_".indexOf(ch) >= 0) && (pos < input.length()))
            {
                token.append(input.charAt(pos++));
                linepos++;
                ch = pos == input.length() ? 0 : input.charAt(pos);
            }
            // Remove optional white spaces after function or variable name
            if (Character.isWhitespace(ch))
            {
                while (Character.isWhitespace(ch) && pos < input.length())
                {
                    ch = input.charAt(pos++);
                    linepos++;
                    if (ch=='\n')
                    {
                        lineno++;
                        linepos = 0;
                    }
                }
                pos--;
                linepos--;
            }
            if (previousToken != null && (
                    previousToken.type == Token.TokenType.VARIABLE ||
                    previousToken.type == Token.TokenType.FUNCTION ||
                    previousToken.type == Token.TokenType.LITERAL ||
                    previousToken.type == Token.TokenType.CLOSE_PAREN ||
                    previousToken.type == Token.TokenType.HEX_LITERAL ||
                    previousToken.type == Token.TokenType.STRINGPARAM ) )
            {
                throw new Expression.ExpressionException(this.expression, previousToken, token.surface +" is not allowed after "+previousToken.surface);
            }
            token.type = ch == '(' ? Token.TokenType.FUNCTION : Token.TokenType.VARIABLE;
        }
        else if (ch == '(' || ch == ')' || ch == ',')
        {
            if (ch == '(')
            {
                token.type = Token.TokenType.OPEN_PAREN;
            }
            else if (ch == ')')
            {
                token.type = Token.TokenType.CLOSE_PAREN;
            }
            else
            {
                token.type = Token.TokenType.COMMA;
            }
            token.append(ch);
            pos++;
            linepos++;
        }
        else
        {
            String greedyMatch = "";
            int initialPos = pos;
            ch = input.charAt(pos);
            int validOperatorSeenUntil = -1;
            while (!Character.isLetter(ch) && !Character.isDigit(ch) && "_".indexOf(ch) < 0
                    && !Character.isWhitespace(ch) && ch != '(' && ch != ')' && ch != ','
                    && (pos < input.length()))
            {
                greedyMatch += ch;
                pos++;
                linepos++;
                if (this.expression.isAnOperator(greedyMatch))
                {
                    validOperatorSeenUntil = pos;
                }
                ch = pos == input.length() ? 0 : input.charAt(pos);
            }
            if (validOperatorSeenUntil != -1)
            {
                token.append(input.substring(initialPos, validOperatorSeenUntil));
                pos = validOperatorSeenUntil;
            }
            else
            {
                token.append(greedyMatch);
            }

            if (previousToken == null || previousToken.type == Token.TokenType.OPERATOR
                    || previousToken.type == Token.TokenType.OPEN_PAREN || previousToken.type == Token.TokenType.COMMA)
            {
                token.surface += "u";
                token.type = Token.TokenType.UNARY_OPERATOR;
            }
            else
            {
                token.type = Token.TokenType.OPERATOR;
            }
        }
        return previousToken = token;
    }

    @Override
    public void remove()
    {
        throw new Expression.InternalExpressionException("remove() not supported");
    }

    public static class Token
    {
        enum TokenType
        {
            VARIABLE, FUNCTION, LITERAL, OPERATOR, UNARY_OPERATOR,
            OPEN_PAREN, COMMA, CLOSE_PAREN, HEX_LITERAL, STRINGPARAM
        }
        public String surface = "";
        public TokenType type;
        public int pos;
        public int linepos;
        public int lineno;

        public void append(char c)
        {
            surface += c;
        }

        public void append(String s)
        {
            surface += s;
        }

        public char charAt(int pos)
        {
            return surface.charAt(pos);
        }

        public int length()
        {
            return surface.length();
        }

        @Override
        public String toString()
        {
            return surface;
        }
    }
}
