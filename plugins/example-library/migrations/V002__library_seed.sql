-- 图书借阅示例：种子数据（可重复脚本——ON CONFLICT DO NOTHING，重复安装不重复插入）。
INSERT INTO example_library_book (title, author, price) VALUES
    ('数据密集型应用系统设计', 'Kleppmann', 108.00),
    ('软件工程：实践者的研究方法', 'Pressman', 89.50),
    ('模块化单体：演进式架构', 'Knoernschild', 69.00)
ON CONFLICT (title) DO NOTHING;
