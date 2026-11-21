//! 运行期 SELinux 策略热修补。
//!
//! ksud 解析策略语言的一个子集（模块 `sepolicy.rule`、每包 profile 片段），把 `{a b}` 这类语句
//! 展开成原子命令，再通过一次 supercall 把整批规则交给内核；内核复制内存策略、应用后整体替换。
//! 应用规则时使用宽松模式，只有 `sepolicy check` 使用严格模式，避免一行坏规则中断开机整批处理。

use anyhow::{Context, Result, bail};
use derive_new::new;
use nom::{
    AsChar, IResult, Parser,
    branch::alt,
    bytes::complete::{tag, take_while, take_while_m_n, take_while1},
    character::complete::{space0, space1},
    combinator::map,
};
use std::{path::Path, vec};

/// 一个或多个策略对象名：单个词、`{a b c}` 或 `*` 展开后的结果。
type SeObject<'a> = Vec<&'a str>;

/// 策略词中允许的字符。其他字符（引号、冒号、点号等）一律拒绝，而不是原样传给内核。
fn is_sepolicy_char(c: char) -> bool {
    c.is_alphanum() || c == '_' || c == '-'
}

/// 单个裸词，用于不允许列表的位置（类型名、class 等）。
fn parse_single_word(input: &str) -> IResult<&str, &str> {
    take_while1(is_sepolicy_char).parse(input)
}

/// 解析 `{ obj1 obj2 ... }` 集合。
///
/// 1..100 字符的上限用于防止畸形文件吞掉后面的全部输入；按空白切分后调用方仍要求至少有一个词。
fn parse_bracket_objs(input: &str) -> IResult<&str, SeObject<'_>> {
    let (input, (_, words, _)) = (
        tag("{"),
        take_while_m_n(1, 100, |c: char| is_sepolicy_char(c) || c.is_whitespace()),
        tag("}"),
    )
        .parse(input)?;
    let objs: SeObject<'_> = words.split_whitespace().collect();
    if objs.is_empty() {
        return Err(nom::Err::Error(nom::error::Error::new(
            input,
            nom::error::ErrorKind::Many1,
        )));
    }
    Ok((input, objs))
}

/// 解析单个对象名并包装成单元素列表。
fn parse_single_obj(input: &str) -> IResult<&str, SeObject<'_>> {
    let (input, word) = take_while1(is_sepolicy_char).parse(input)?;
    Ok((input, vec![word]))
}

/// 解析通配符 `*`，内核收到空串时会把它当作 ALL。
fn parse_star(input: &str) -> IResult<&str, SeObject<'_>> {
    let (input, _) = tag("*").parse(input)?;
    Ok((input, vec!["*"]))
}

// 1. 单个 sepolicy 词
// 2. 集合形式：{ obj1 obj2 obj3 ...}
// 3. 通配符 *
/// 解析一个策略对象参数：单词、集合或通配符。
fn parse_seobj(input: &str) -> IResult<&str, SeObject<'_>> {
    let (input, strs) = alt((parse_single_obj, parse_bracket_objs, parse_star)).parse(input)?;
    Ok((input, strs))
}

/// 同上，但不接受 `*`；类型状态、类型属性等位置不允许通配。
fn parse_seobj_no_star(input: &str) -> IResult<&str, SeObject<'_>> {
    let (input, strs) = alt((parse_single_obj, parse_bracket_objs)).parse(input)?;
    Ok((input, strs))
}

/// 每种语句共用的一步解析接口，把 nom 的解析器统一到 `Self::parse` 上。
trait SeObjectParser<'a> {
    fn parse(input: &'a str) -> IResult<&'a str, Self>
    where
        Self: Sized;
}

/// `allow`/`deny`/`auditallow`/`dontaudit` 语句：源、目标、class 和权限都可以是列表。
#[derive(Debug, PartialEq, Eq, new)]
struct NormalPerm<'a> {
    op: &'a str,
    source: SeObject<'a>,
    target: SeObject<'a>,
    class: SeObject<'a>,
    perm: SeObject<'a>,
}

/// `allowxperm` 系列：在普通权限之外多一个 ioctl 操作名。
#[derive(Debug, PartialEq, Eq, new)]
struct XPerm<'a> {
    op: &'a str,
    source: SeObject<'a>,
    target: SeObject<'a>,
    class: SeObject<'a>,
    operation: &'a str,
    perm_set: SeObject<'a>,
}

/// `permissive`/`enforce`：切换一个或多个类型的强制模式。
#[derive(Debug, PartialEq, Eq, new)]
struct TypeState<'a> {
    op: &'a str,
    stype: SeObject<'a>,
}

/// `typeattribute`/`attradd`：把类型加入属性。
#[derive(Debug, PartialEq, Eq, new)]
struct TypeAttr<'a> {
    stype: SeObject<'a>,
    sattr: SeObject<'a>,
}

/// `type`：声明一个新类型及其属性。
#[derive(Debug, PartialEq, Eq, new)]
struct Type<'a> {
    name: &'a str,
    attrs: SeObject<'a>,
}

/// `attribute`：声明一个属性。
#[derive(Debug, PartialEq, Eq, new)]
struct Attr<'a> {
    name: &'a str,
}

/// `type_transition`/`name_transition`：目标对象上的自动类型转换。
#[derive(Debug, PartialEq, Eq, new)]
struct TypeTransition<'a> {
    source: &'a str,
    target: &'a str,
    class: &'a str,
    default_type: &'a str,
    object_name: Option<&'a str>,
}

/// `type_change`/`type_member`：重新标记已有对象时的类型规则。
#[derive(Debug, PartialEq, Eq, new)]
struct TypeChange<'a> {
    op: &'a str,
    source: &'a str,
    target: &'a str,
    class: &'a str,
    default_type: &'a str,
}

/// `genfscon`：为伪文件系统路径指定固定 context。
#[derive(Debug, PartialEq, Eq, new)]
struct GenFsCon<'a> {
    fs_name: &'a str,
    partial_path: &'a str,
    fs_context: &'a str,
}

/// 一条已解析的策略语句；变体与内核支持的 sepolicy 命令一一对应。
#[derive(Debug)]
enum PolicyStatement<'a> {
    // "allow *source_type *target_type *class *perm_set"
    // "deny *source_type *target_type *class *perm_set"
    // "auditallow *source_type *target_type *class *perm_set"
    // "dontaudit *source_type *target_type *class *perm_set"
    NormalPerm(NormalPerm<'a>),

    // "allowxperm *source_type *target_type *class operation xperm_set"
    // "auditallowxperm *source_type *target_type *class operation xperm_set"
    // "dontauditxperm *source_type *target_type *class operation xperm_set"
    XPerm(XPerm<'a>),

    // "permissive ^type"
    // "enforce ^type"
    TypeState(TypeState<'a>),

    // "type type_name ^(attribute)"
    Type(Type<'a>),

    // "typeattribute ^type ^attribute"
    TypeAttr(TypeAttr<'a>),

    // "attribute ^attribute"
    Attr(Attr<'a>),

    // "type_transition source_type target_type class default_type (object_name)"
    TypeTransition(TypeTransition<'a>),

    // "type_change source_type target_type class default_type"
    // "type_member source_type target_type class default_type"
    TypeChange(TypeChange<'a>),

    // "genfscon fs_name partial_path fs_context"
    GenFsCon(GenFsCon<'a>),
}

impl<'a> SeObjectParser<'a> for NormalPerm<'a> {
    fn parse(input: &'a str) -> IResult<&'a str, Self> {
        let (input, op) = alt((
            tag("allow"),
            tag("deny"),
            tag("auditallow"),
            tag("dontaudit"),
        ))
        .parse(input)?;

        let (input, _) = space1(input)?;
        let (input, source) = parse_seobj(input)?;
        let (input, _) = space1(input)?;
        let (input, target) = parse_seobj(input)?;
        let (input, _) = space1(input)?;
        let (input, class) = parse_seobj(input)?;
        let (input, _) = space1(input)?;
        let (input, perm) = parse_seobj(input)?;
        Ok((input, NormalPerm::new(op, source, target, class, perm)))
    }
}

impl<'a> SeObjectParser<'a> for XPerm<'a> {
    fn parse(input: &'a str) -> IResult<&'a str, Self> {
        let (input, op) = alt((
            tag("allowxperm"),
            tag("auditallowxperm"),
            tag("dontauditxperm"),
        ))
        .parse(input)?;

        let (input, _) = space1(input)?;
        let (input, source) = parse_seobj(input)?;
        let (input, _) = space1(input)?;
        let (input, target) = parse_seobj(input)?;
        let (input, _) = space1(input)?;
        let (input, class) = parse_seobj(input)?;
        let (input, _) = space1(input)?;
        let (input, operation) = parse_single_word(input)?;
        let (input, _) = space1(input)?;
        let (input, perm_set) = parse_seobj(input)?;

        Ok((
            input,
            XPerm::new(op, source, target, class, operation, perm_set),
        ))
    }
}

impl<'a> SeObjectParser<'a> for TypeState<'a> {
    fn parse(input: &'a str) -> IResult<&'a str, Self> {
        let (input, op) = alt((tag("permissive"), tag("enforce"))).parse(input)?;

        let (input, _) = space1(input)?;
        let (input, stype) = parse_seobj_no_star(input)?;

        Ok((input, TypeState::new(op, stype)))
    }
}

impl<'a> SeObjectParser<'a> for Type<'a> {
    fn parse(input: &'a str) -> IResult<&'a str, Self> {
        // `tag("type")` 也会匹配到 `typeattribute`/`type_transition` 的开头，但紧跟的 `space1`
        // 会因下划线而失败，于是 alt 会继续尝试后面的解析器。
        let (input, _) = tag("type")(input)?;
        let (input, _) = space1(input)?;
        let (input, name) = parse_single_word(input)?;

        if input.is_empty() {
            return Ok((input, Type::new(name, vec!["domain"]))); // 默认为 domain
        }

        let (input, _) = space1(input)?;
        let (input, attrs) = parse_seobj_no_star(input)?;

        Ok((input, Type::new(name, attrs)))
    }
}

impl<'a> SeObjectParser<'a> for TypeAttr<'a> {
    fn parse(input: &'a str) -> IResult<&'a str, Self> {
        let (input, _) = alt((tag("typeattribute"), tag("attradd"))).parse(input)?;
        let (input, _) = space1(input)?;
        let (input, stype) = parse_seobj_no_star(input)?;
        let (input, _) = space1(input)?;
        let (input, attr) = parse_seobj_no_star(input)?;

        Ok((input, TypeAttr::new(stype, attr)))
    }
}

impl<'a> SeObjectParser<'a> for Attr<'a> {
    fn parse(input: &'a str) -> IResult<&'a str, Self> {
        let (input, _) = tag("attribute")(input)?;
        let (input, _) = space1(input)?;
        let (input, attr) = parse_single_word(input)?;

        Ok((input, Attr::new(attr)))
    }
}

impl<'a> SeObjectParser<'a> for TypeTransition<'a> {
    fn parse(input: &'a str) -> IResult<&'a str, Self> {
        let (input, _) = alt((tag("type_transition"), tag("name_transition"))).parse(input)?;
        let (input, _) = space1(input)?;
        let (input, source) = parse_single_word(input)?;
        let (input, _) = space1(input)?;
        let (input, target) = parse_single_word(input)?;
        let (input, _) = space1(input)?;
        let (input, class) = parse_single_word(input)?;
        let (input, _) = space1(input)?;
        let (input, default) = parse_single_word(input)?;

        // 对象名是可选的：语句到此结束就表示没有对象名。
        if input.is_empty() {
            return Ok((
                input,
                TypeTransition::new(source, target, class, default, None),
            ));
        }

        let (input, _) = space1(input)?;
        let (input, object) = parse_single_word(input)?;

        Ok((
            input,
            TypeTransition::new(source, target, class, default, Some(object)),
        ))
    }
}

impl<'a> SeObjectParser<'a> for TypeChange<'a> {
    fn parse(input: &'a str) -> IResult<&'a str, Self> {
        let (input, op) = alt((tag("type_change"), tag("type_member"))).parse(input)?;
        let (input, _) = space1(input)?;
        let (input, source) = parse_single_word(input)?;
        let (input, _) = space1(input)?;
        let (input, target) = parse_single_word(input)?;
        let (input, _) = space1(input)?;
        let (input, class) = parse_single_word(input)?;
        let (input, _) = space1(input)?;
        let (input, default) = parse_single_word(input)?;

        Ok((input, TypeChange::new(op, source, target, class, default)))
    }
}

impl<'a> SeObjectParser<'a> for GenFsCon<'a> {
    fn parse(input: &'a str) -> IResult<&'a str, Self>
    where
        Self: Sized,
    {
        let (input, _) = tag("genfscon")(input)?;
        let (input, _) = space1(input)?;
        let (input, fs) = parse_single_word(input)?;
        let (input, _) = space1(input)?;
        let (input, path) = parse_single_word(input)?;
        let (input, _) = space1(input)?;
        let (input, context) = parse_single_word(input)?;
        Ok((input, GenFsCon::new(fs, path, context)))
    }
}

impl<'a> PolicyStatement<'a> {
    /// 解析一条语句，允许前后空白和结尾分号。
    fn parse(input: &'a str) -> IResult<&'a str, Self> {
        let (input, _) = space0(input)?;
        // 顺序很关键：`allow` 先于 `allowxperm`、`type` 先于 `typeattribute` 尝试，靠的都是关键字
        // 后面必须紧跟空白，因此前缀相同的语句不会互相吞掉。
        let (input, statement) = alt((
            map(NormalPerm::parse, PolicyStatement::NormalPerm),
            map(XPerm::parse, PolicyStatement::XPerm),
            map(TypeState::parse, PolicyStatement::TypeState),
            map(Type::parse, PolicyStatement::Type),
            map(TypeAttr::parse, PolicyStatement::TypeAttr),
            map(Attr::parse, PolicyStatement::Attr),
            map(TypeTransition::parse, PolicyStatement::TypeTransition),
            map(TypeChange::parse, PolicyStatement::TypeChange),
            map(GenFsCon::parse, PolicyStatement::GenFsCon),
        ))
        .parse(input)?;
        let (input, _) = space0(input)?;
        // 允许语句末尾带一个或多个分号。
        let (input, _) = take_while(|c| c == ';')(input)?;
        let (input, _) = space0(input)?;
        Ok((input, statement))
    }
}

/// 把整段策略文本解析成语句列表。
///
/// 同时按换行和分号切分，因此两种书写风格都能接受；空行和 `#` 注释会被跳过。`strict` 为真时
/// 无法解析的行直接报错（`sepolicy check` 使用），否则该行被静默丢弃（应用模块规则时使用，
/// 避免一行坏规则中断整批）。
fn parse_sepolicy<'a, 'b>(input: &'b str, strict: bool) -> Result<Vec<PolicyStatement<'a>>>
where
    'b: 'a,
{
    let mut statements = vec![];

    for line in input.split(['\n', ';']) {
        let trimmed_line = line.trim();
        if trimmed_line.is_empty() || trimmed_line.starts_with('#') {
            continue;
        }
        if let Ok((_, statement)) = PolicyStatement::parse(trimmed_line) {
            statements.push(statement);
        } else if strict {
            bail!("Failed to parse policy statement: {line}")
        }
    }
    Ok(statements)
}

/// 内核命令中一个参数槽的编码形式。
#[derive(Debug, Default)]
enum PolicyObject {
    /// 通配符，序列化为空串；内核把空串解释为 ALL。
    All,
    /// 普通名字，按字节序列化。
    One(Vec<u8>),
    /// 该槽位不使用，同样序列化为空串。
    #[default]
    None,
}

impl TryFrom<&str> for PolicyObject {
    type Error = anyhow::Error;
    /// 拒绝 NUL，因为内核按 C 字符串解析这些参数。
    fn try_from(s: &str) -> Result<Self> {
        anyhow::ensure!(!s.as_bytes().contains(&0), "policy object contains NUL");
        if s == "*" {
            return Ok(Self::All);
        }
        Ok(Self::One(s.as_bytes().to_vec()))
    }
}

/// 原子语句，例如：allow domain1 domain2:file1 read;
/// 普通语句会被展开为原子语句，例如：
/// allow domain1 domain2:file1 { read write }; 会展开为两条原子语句
/// allow domain1 domain2:file1 read;allow domain1 domain2:file1 write;
#[allow(clippy::too_many_arguments)]
#[derive(Debug, new)]
struct AtomicStatement {
    cmd: u32,
    subcmd: u32,
    sepol1: PolicyObject,
    sepol2: PolicyObject,
    sepol3: PolicyObject,
    sepol4: PolicyObject,
    sepol5: PolicyObject,
    sepol6: PolicyObject,
    sepol7: PolicyObject,
}

impl<'a> TryFrom<&'a NormalPerm<'a>> for Vec<AtomicStatement> {
    type Error = anyhow::Error;
    fn try_from(perm: &'a NormalPerm<'a>) -> Result<Self> {
        let mut result = vec![];
        // 下划线分支只是兜底：解析器只会产生上面四种 op。
        let subcmd = match perm.op {
            "allow" => crate::ksu_uapi::KSU_SEPOLICY_SUBCMD_NORMAL_PERM_ALLOW,
            "deny" => crate::ksu_uapi::KSU_SEPOLICY_SUBCMD_NORMAL_PERM_DENY,
            "auditallow" => crate::ksu_uapi::KSU_SEPOLICY_SUBCMD_NORMAL_PERM_AUDITALLOW,
            "dontaudit" => crate::ksu_uapi::KSU_SEPOLICY_SUBCMD_NORMAL_PERM_DONTAUDIT,
            _ => 0,
        };
        // 对列表参数做笛卡尔积展开，每条原子语句只携带一个具体对象。
        for &s in &perm.source {
            for &t in &perm.target {
                for &c in &perm.class {
                    for &p in &perm.perm {
                        result.push(AtomicStatement {
                            cmd: crate::ksu_uapi::KSU_SEPOLICY_CMD_NORMAL_PERM,
                            subcmd,
                            sepol1: s.try_into()?,
                            sepol2: t.try_into()?,
                            sepol3: c.try_into()?,
                            sepol4: p.try_into()?,
                            sepol5: PolicyObject::None,
                            sepol6: PolicyObject::None,
                            sepol7: PolicyObject::None,
                        });
                    }
                }
            }
        }
        Ok(result)
    }
}

impl<'a> TryFrom<&'a XPerm<'a>> for Vec<AtomicStatement> {
    type Error = anyhow::Error;
    fn try_from(perm: &'a XPerm<'a>) -> Result<Self> {
        let mut result = vec![];
        let subcmd = match perm.op {
            "allowxperm" => crate::ksu_uapi::KSU_SEPOLICY_SUBCMD_XPERM_ALLOW,
            "auditallowxperm" => crate::ksu_uapi::KSU_SEPOLICY_SUBCMD_XPERM_AUDITALLOW,
            "dontauditxperm" => crate::ksu_uapi::KSU_SEPOLICY_SUBCMD_XPERM_DONTAUDIT,
            _ => 0,
        };
        for &s in &perm.source {
            for &t in &perm.target {
                for &c in &perm.class {
                    for &r in &perm.perm_set {
                        result.push(AtomicStatement {
                            cmd: crate::ksu_uapi::KSU_SEPOLICY_CMD_XPERM,
                            subcmd,
                            sepol1: s.try_into()?,
                            sepol2: t.try_into()?,
                            sepol3: c.try_into()?,
                            sepol4: perm.operation.try_into()?,
                            sepol5: r.try_into()?,
                            sepol6: PolicyObject::None,
                            sepol7: PolicyObject::None,
                        });
                    }
                }
            }
        }
        Ok(result)
    }
}

impl<'a> TryFrom<&'a TypeState<'a>> for Vec<AtomicStatement> {
    type Error = anyhow::Error;
    fn try_from(perm: &'a TypeState<'a>) -> Result<Self> {
        let mut result = vec![];
        let subcmd = match perm.op {
            "permissive" => crate::ksu_uapi::KSU_SEPOLICY_SUBCMD_TYPE_STATE_PERMISSIVE,
            "enforce" => crate::ksu_uapi::KSU_SEPOLICY_SUBCMD_TYPE_STATE_ENFORCE,
            _ => 0,
        };
        for &t in &perm.stype {
            result.push(AtomicStatement {
                cmd: crate::ksu_uapi::KSU_SEPOLICY_CMD_TYPE_STATE,
                subcmd,
                sepol1: t.try_into()?,
                sepol2: PolicyObject::None,
                sepol3: PolicyObject::None,
                sepol4: PolicyObject::None,
                sepol5: PolicyObject::None,
                sepol6: PolicyObject::None,
                sepol7: PolicyObject::None,
            });
        }
        Ok(result)
    }
}

impl<'a> TryFrom<&'a Type<'a>> for Vec<AtomicStatement> {
    type Error = anyhow::Error;
    fn try_from(perm: &'a Type<'a>) -> Result<Self> {
        let mut result = vec![];
        for &attr in &perm.attrs {
            result.push(AtomicStatement {
                cmd: crate::ksu_uapi::KSU_SEPOLICY_CMD_TYPE,
                subcmd: 0,
                sepol1: perm.name.try_into()?,
                sepol2: attr.try_into()?,
                sepol3: PolicyObject::None,
                sepol4: PolicyObject::None,
                sepol5: PolicyObject::None,
                sepol6: PolicyObject::None,
                sepol7: PolicyObject::None,
            });
        }
        Ok(result)
    }
}

impl<'a> TryFrom<&'a TypeAttr<'a>> for Vec<AtomicStatement> {
    type Error = anyhow::Error;
    fn try_from(perm: &'a TypeAttr<'a>) -> Result<Self> {
        let mut result = vec![];
        for &t in &perm.stype {
            for &attr in &perm.sattr {
                result.push(AtomicStatement {
                    cmd: crate::ksu_uapi::KSU_SEPOLICY_CMD_TYPE_ATTR,
                    subcmd: 0,
                    sepol1: t.try_into()?,
                    sepol2: attr.try_into()?,
                    sepol3: PolicyObject::None,
                    sepol4: PolicyObject::None,
                    sepol5: PolicyObject::None,
                    sepol6: PolicyObject::None,
                    sepol7: PolicyObject::None,
                });
            }
        }
        Ok(result)
    }
}

impl<'a> TryFrom<&'a Attr<'a>> for Vec<AtomicStatement> {
    type Error = anyhow::Error;
    fn try_from(perm: &'a Attr<'a>) -> Result<Self> {
        let result = vec![AtomicStatement {
            cmd: crate::ksu_uapi::KSU_SEPOLICY_CMD_ATTR,
            subcmd: 0,
            sepol1: perm.name.try_into()?,
            sepol2: PolicyObject::None,
            sepol3: PolicyObject::None,
            sepol4: PolicyObject::None,
            sepol5: PolicyObject::None,
            sepol6: PolicyObject::None,
            sepol7: PolicyObject::None,
        }];
        Ok(result)
    }
}

impl<'a> TryFrom<&'a TypeTransition<'a>> for Vec<AtomicStatement> {
    type Error = anyhow::Error;
    fn try_from(perm: &'a TypeTransition<'a>) -> Result<Self> {
        let mut result = vec![];
        let obj = match perm.object_name {
            Some(obj) => obj.try_into()?,
            None => PolicyObject::None,
        };
        result.push(AtomicStatement {
            cmd: crate::ksu_uapi::KSU_SEPOLICY_CMD_TYPE_TRANSITION,
            subcmd: 0,
            sepol1: perm.source.try_into()?,
            sepol2: perm.target.try_into()?,
            sepol3: perm.class.try_into()?,
            sepol4: perm.default_type.try_into()?,
            sepol5: obj,
            sepol6: PolicyObject::None,
            sepol7: PolicyObject::None,
        });
        Ok(result)
    }
}

impl<'a> TryFrom<&'a TypeChange<'a>> for Vec<AtomicStatement> {
    type Error = anyhow::Error;
    fn try_from(perm: &'a TypeChange<'a>) -> Result<Self> {
        let mut result = vec![];
        let subcmd = match perm.op {
            "type_change" => crate::ksu_uapi::KSU_SEPOLICY_SUBCMD_TYPE_CHANGE_CHANGE,
            "type_member" => crate::ksu_uapi::KSU_SEPOLICY_SUBCMD_TYPE_CHANGE_MEMBER,
            _ => 0,
        };
        result.push(AtomicStatement {
            cmd: crate::ksu_uapi::KSU_SEPOLICY_CMD_TYPE_CHANGE,
            subcmd,
            sepol1: perm.source.try_into()?,
            sepol2: perm.target.try_into()?,
            sepol3: perm.class.try_into()?,
            sepol4: perm.default_type.try_into()?,
            sepol5: PolicyObject::None,
            sepol6: PolicyObject::None,
            sepol7: PolicyObject::None,
        });
        Ok(result)
    }
}

impl<'a> TryFrom<&'a GenFsCon<'a>> for Vec<AtomicStatement> {
    type Error = anyhow::Error;
    fn try_from(perm: &'a GenFsCon<'a>) -> Result<Self> {
        let result = vec![AtomicStatement {
            cmd: crate::ksu_uapi::KSU_SEPOLICY_CMD_GENFSCON,
            subcmd: 0,
            sepol1: perm.fs_name.try_into()?,
            sepol2: perm.partial_path.try_into()?,
            sepol3: perm.fs_context.try_into()?,
            sepol4: PolicyObject::None,
            sepol5: PolicyObject::None,
            sepol6: PolicyObject::None,
            sepol7: PolicyObject::None,
        }];
        Ok(result)
    }
}

impl<'a> TryFrom<&'a PolicyStatement<'a>> for Vec<AtomicStatement> {
    type Error = anyhow::Error;
    fn try_from(value: &'a PolicyStatement) -> Result<Self> {
        match value {
            PolicyStatement::NormalPerm(perm) => perm.try_into(),
            PolicyStatement::XPerm(perm) => perm.try_into(),
            PolicyStatement::TypeState(perm) => perm.try_into(),
            PolicyStatement::Type(perm) => perm.try_into(),
            PolicyStatement::TypeAttr(perm) => perm.try_into(),
            PolicyStatement::Attr(perm) => perm.try_into(),
            PolicyStatement::TypeTransition(perm) => perm.try_into(),
            PolicyStatement::TypeChange(perm) => perm.try_into(),
            PolicyStatement::GenFsCon(perm) => perm.try_into(),
        }
    }
}

/// 每种命令在线上格式中携带的参数个数。
///
/// 必须与内核 `sepol_expected_argc` 完全一致；它决定 `AtomicStatement` 的七个槽位中前几个会被
/// 序列化。
const fn cmd_expected_argc(cmd: u32) -> Option<usize> {
    match cmd {
        crate::ksu_uapi::KSU_SEPOLICY_CMD_NORMAL_PERM
        | crate::ksu_uapi::KSU_SEPOLICY_CMD_TYPE_CHANGE => Some(4),
        crate::ksu_uapi::KSU_SEPOLICY_CMD_XPERM
        | crate::ksu_uapi::KSU_SEPOLICY_CMD_TYPE_TRANSITION => Some(5),
        crate::ksu_uapi::KSU_SEPOLICY_CMD_TYPE_STATE | crate::ksu_uapi::KSU_SEPOLICY_CMD_ATTR => {
            Some(1)
        }
        crate::ksu_uapi::KSU_SEPOLICY_CMD_TYPE | crate::ksu_uapi::KSU_SEPOLICY_CMD_TYPE_ATTR => {
            Some(2)
        }
        crate::ksu_uapi::KSU_SEPOLICY_CMD_GENFSCON => Some(3),
        _ => None,
    }
}

/// 按内核的线上格式写一个参数：u32 本机字节序长度、原始字节、结尾 NUL。
///
/// `None` 和 `All` 都编码成长度 0，内核读到空串会把它当作 ALL，这与 libsepol 的约定一致。
fn encode_policy_object(payload: &mut Vec<u8>, object: &PolicyObject) -> Result<()> {
    let bytes = match object {
        PolicyObject::None | PolicyObject::All => &[][..],
        PolicyObject::One(value) => value.as_slice(),
    };

    let len = u32::try_from(bytes.len()).context("policy object too long to encode")?;
    payload.extend_from_slice(&len.to_ne_bytes());
    payload.extend_from_slice(bytes);
    // 内核要求字符串以 NUL 结尾，且 NUL 不计入 len。
    payload.push(0);

    Ok(())
}

/// 写一条原子语句：命令头（cmd、subcmd）加上该命令所需的参数。
fn append_atomic_statement(payload: &mut Vec<u8>, statement: &AtomicStatement) -> Result<()> {
    let expected_argc = cmd_expected_argc(statement.cmd)
        .ok_or_else(|| anyhow::anyhow!("unknown sepolicy cmd {}", statement.cmd))?;

    payload.extend_from_slice(&statement.cmd.to_ne_bytes());
    payload.extend_from_slice(&statement.subcmd.to_ne_bytes());

    let args = [
        &statement.sepol1,
        &statement.sepol2,
        &statement.sepol3,
        &statement.sepol4,
        &statement.sepol5,
        &statement.sepol6,
        &statement.sepol7,
    ];

    // 内核只按命令读取固定数量的参数，多余的槽位不发送。
    for object in args.iter().take(expected_argc) {
        encode_policy_object(payload, object)?;
    }

    Ok(())
}

/// 把整批原子语句序列化成一个连续的 payload。
fn serialize_atomic_statements(statements: &[AtomicStatement]) -> Result<Vec<u8>> {
    let mut payload = vec![];
    for statement in statements {
        append_atomic_statement(&mut payload, statement)?;
    }
    Ok(payload)
}

/// 把语句列表展开成按顺序排列的原子语句列表。
///
/// 这里的 `?` 意味着某条语句无法转换（例如参数含 NUL）时整批都会失败。
fn flatten_atomic_statements<'a>(
    statements: &'a [PolicyStatement<'a>],
) -> Result<Vec<AtomicStatement>> {
    let mut policies = vec![];
    for statement in statements {
        let mut expanded: Vec<AtomicStatement> = statement.try_into()?;
        policies.append(&mut expanded);
    }
    Ok(policies)
}

/// 用一次 supercall 应用整批规则。
///
/// 内核返回成功应用的命令数；`strict` 决定部分成功或调用失败时是否算错误。非严格模式用于
/// 开机流程，保证一条坏规则不会让整批（乃至开机）失败。
fn apply_rules_batch<'a>(statements: &'a [PolicyStatement<'a>], strict: bool) -> Result<()> {
    let policies = flatten_atomic_statements(statements)?;
    if policies.is_empty() {
        return Ok(());
    }

    let payload = serialize_atomic_statements(&policies)?;

    match crate::ksucalls::set_sepolicy(payload.as_ptr(), payload.len() as u64) {
        Ok(applied_count) => {
            // 内核用负数错误码表示失败，转成 usize 失败即说明这一点。
            let applied_count = usize::try_from(applied_count)
                .context("kernel returned negative sepolicy applied count")?;
            if applied_count < policies.len() {
                let err = anyhow::anyhow!(
                    "apply sepolicy batch partially succeeded: {applied_count}/{}",
                    policies.len()
                );
                if strict {
                    return Err(err);
                }
                log::warn!("{err}");
            }
        }
        Err(e) => {
            log::warn!("apply sepolicy batch failed: {e}");
            if strict {
                return Err(anyhow::anyhow!("apply sepolicy batch failed: {e}"));
            }
        }
    }

    Ok(())
}

/// 解析并应用一段内联策略文本，宽松模式。
///
/// 解析出的语句会先打印到 stdout 便于调试，然后整批提交给内核。
pub fn live_patch(policy: &str) -> Result<()> {
    let result = parse_sepolicy(policy.trim(), false)?;
    for statement in &result {
        println!("{statement:?}");
    }
    apply_rules_batch(&result, false)?;
    Ok(())
}

/// 读取文件内容并按 `live_patch` 的方式应用。
pub fn apply_file<P: AsRef<Path>>(path: P) -> Result<()> {
    let input = std::fs::read_to_string(path)?;
    live_patch(&input)
}

/// 只做解析检查，不修改策略。
///
/// `policy` 既可以是一个已存在的文件路径，也可以直接是策略文本；这里强制使用严格模式，遇到无法
/// 解析的行就报错，供 `ksud sepolicy check` 使用。
pub fn check_rule(policy: &str) -> Result<()> {
    let path = Path::new(policy);
    let policy = if path.exists() {
        std::fs::read_to_string(path)?
    } else {
        policy.to_string()
    };
    parse_sepolicy(policy.trim(), true)?;
    Ok(())
}
