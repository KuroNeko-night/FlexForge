-- 图书借阅示例：插件自有馆藏物理台账（docs/07 迁移对象须以插件短名前缀命名）。
-- 业务记录经 data_record 动态存储（docs/08 §5），本表承载插件自有对象与
-- 馆藏价的数据库层兜底（应用层规则见 library-book.json price min:0）。
CREATE TABLE IF NOT EXISTS example_library_book (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title      VARCHAR(120) NOT NULL,
    author     VARCHAR(60)  NOT NULL,
    price      NUMERIC(20, 6),
    CONSTRAINT uq_example_library_book_title UNIQUE (title),
    CONSTRAINT ck_example_library_book_price_nonneg CHECK (price IS NULL OR price >= 0)
);
